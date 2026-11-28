package com.teamani.moamoa.settlement

import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
import com.teamani.moamoa.settlement.dto.SettlementUpdateRequest
import com.teamani.moamoa.settlement.entity.PaymentStatus
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.settlement.service.SettlementService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.reset
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

// 실제 트랜잭션·DB에서 정산 수정(4-11)과 납부 제출(4-4)이 동시에 들어올 때 같은 정산 잠금으로 순서대로 처리되는지 검증
@SpringBootTest
@ActiveProfiles("test")
class SettlementUpdateConcurrencyTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var settlementService: SettlementService
    @Autowired lateinit var settlementRepository: SettlementRepository
    @Autowired lateinit var meetingRepository: MeetingRepository
    @Autowired lateinit var memberRepository: MemberRepository

    // 요청을 원하는 시점에 멈춰 경합 순서를 재현하기 위해 spy 사용
    @MockitoSpyBean lateinit var settlementMemberRepository: SettlementMemberRepository

    init {
        afterTest {
            reset(settlementMemberRepository)
        }

        Given("관리자와 구성원 1명이 대상인 정산이 있을 때") {
            When("납부 제출이 정산을 잠근 직후 금액 수정이 들어오면") {
                Then("수정은 제출이 끝날 때까지 기다린 뒤 제출 기록을 보고 422이고 금액은 그대로다") {
                    val saved = savedSettlement()
                    // 제출이 정산을 잠그고 본인 납부 항목을 조회하기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(settlementMemberRepository, pause::hold)).`when`(settlementMemberRepository)
                        .findBySettlementIdAndMemberId(anyLong(), anyLong())
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val submit = executor.start { settlementService.submitPayment(saved.meetingId, MEMBER_USER_ID, saved.settlementId) }
                        pause.awaitPaused()
                        val update = executor.start { updateAmount(saved) }

                        shouldThrow<TimeoutException> { update.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        submit.get(10, TimeUnit.SECONDS).shouldBeNull()
                        (update.get(10, TimeUnit.SECONDS) as ApiException).errorCode shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    settlementRepository.findById(saved.settlementId).orElseThrow().totalAmount shouldBe 30000
                }
            }

            When("금액 수정이 정산을 잠근 직후 납부 제출이 들어오면") {
                Then("제출은 수정이 끝날 때까지 기다린 뒤 바뀐 금액의 납부 항목으로 제출된다") {
                    val saved = savedSettlement()
                    // 수정이 정산을 잠그고 납부 항목을 조회하기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(settlementMemberRepository, pause::hold)).`when`(settlementMemberRepository)
                        .findAllBySettlementIdOrderByIdAsc(anyLong())
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        val update = executor.start { updateAmount(saved) }
                        pause.awaitPaused()
                        val submit = executor.start { settlementService.submitPayment(saved.meetingId, MEMBER_USER_ID, saved.settlementId) }

                        shouldThrow<TimeoutException> { submit.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        update.get(10, TimeUnit.SECONDS).shouldBeNull()
                        submit.get(10, TimeUnit.SECONDS).shouldBeNull()
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    val payment = settlementMemberRepository.findBySettlementIdAndMemberId(saved.settlementId, saved.memberId)
                    payment?.amount shouldBe 20000
                    payment?.status shouldBe PaymentStatus.PENDING
                }
            }

            When("한 관리자가 대상을 바꾸는 중에 다른 관리자가 옛 대상 목록으로 수정하면") {
                Then("뒤 요청은 앞 수정이 끝날 때까지 기다린 뒤 덮어쓰지 않고 409이다") {
                    val saved = savedSettlement()
                    val otherMemberId = checkNotNull(
                        memberRepository.save(Member(meetingId = saved.meetingId, userId = OTHER_USER_ID, role = MemberRole.MEMBER)).id
                    )
                    // 앞 수정이 정산을 잠그고 납부 항목을 조회하기 직전에 멈춤
                    val pause = Pause()
                    doAnswer(beforeReal(settlementMemberRepository, pause::hold)).`when`(settlementMemberRepository)
                        .findAllBySettlementIdOrderByIdAsc(anyLong())
                    val executor = Executors.newFixedThreadPool(2)

                    try {
                        // 앞 수정: [관리자, 구성원] → [관리자, 다른 구성원]
                        val first = executor.start { updateMembers(saved, listOf(saved.adminMemberId, otherMemberId)) }
                        pause.awaitPaused()
                        // 뒤 수정: 옛 화면 기준으로 [관리자, 구성원]을 보냄 (잠금 전에는 바뀐 것이 없어 보임)
                        val second = executor.start { updateMembers(saved, listOf(saved.adminMemberId, saved.memberId)) }

                        shouldThrow<TimeoutException> { second.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
                        pause.release()

                        first.get(10, TimeUnit.SECONDS).shouldBeNull()
                        (second.get(10, TimeUnit.SECONDS) as ApiException).errorCode shouldBe ErrorCode.CONFLICT
                    } finally {
                        pause.release()
                        executor.shutdownNow()
                    }
                    settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(saved.settlementId).map { it.memberId }.toSet() shouldBe
                        setOf(saved.adminMemberId, otherMemberId)
                }
            }
        }
    }

    private data class SavedSettlement(val meetingId: Long, val adminMemberId: Long, val memberId: Long, val settlementId: Long)

    // 관리자(ADMIN_USER_ID)와 구성원(MEMBER_USER_ID)이 대상인 30,000원 정산
    private fun savedSettlement(): SavedSettlement {
        val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = UUID.randomUUID().toString().take(8))).id)
        val adminMemberId = checkNotNull(memberRepository.save(Member(meetingId = meetingId, userId = ADMIN_USER_ID, role = MemberRole.ADMIN)).id)
        val memberId = checkNotNull(memberRepository.save(Member(meetingId = meetingId, userId = MEMBER_USER_ID, role = MemberRole.MEMBER)).id)
        val settlementId = settlementService.createSettlement(
            meetingId,
            ADMIN_USER_ID,
            SettlementCreateRequest(
                title = "회식비",
                totalAmount = 30000,
                memberIds = listOf(adminMemberId, memberId),
                bankName = "국민은행",
                accountNumber = "123456789012",
                accountHolder = "김모아",
                dueAt = OffsetDateTime.parse("2099-10-20T23:59:00+09:00")
            )
        ).settlementId
        return SavedSettlement(meetingId, adminMemberId, memberId, settlementId)
    }

    private fun updateMembers(saved: SavedSettlement, memberIds: List<Long>) =
        settlementService.updateSettlement(
            saved.meetingId, ADMIN_USER_ID, saved.settlementId, SettlementUpdateRequest(memberIds = Optional.of(memberIds))
        )

    // 총액 30,000원 → 40,000원 (2명이라 1인 20,000원)
    private fun updateAmount(saved: SavedSettlement) =
        settlementService.updateSettlement(
            saved.meetingId, ADMIN_USER_ID, saved.settlementId, SettlementUpdateRequest(totalAmount = Optional.of(40000))
        )

    // 끼워 넣은 동작 실행 후 실제 Repository 메서드 호출 (Spring Data 프록시 spy의 기본 응답이 실제 Repository 위임)
    private fun beforeReal(spy: Any, action: () -> Unit) = Answer { invocation ->
        action()
        mockingDetails(spy).mockCreationSettings.defaultAnswer.answer(invocation)
    }

    // 첫 호출만 멈추고, 테스트가 해제할 때까지 대기 (정산 잠금을 쥔 채 멈추므로 H2 잠금 대기 2초 안에 해제)
    private class Pause {
        private val paused = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val first = AtomicBoolean(true)

        fun hold() {
            if (first.getAndSet(false)) {
                paused.countDown()
                released.await(5, TimeUnit.SECONDS)
            }
        }

        fun awaitPaused() {
            paused.await(5, TimeUnit.SECONDS) shouldBe true
        }

        fun release() = released.countDown()
    }

    // 작업을 비동기로 실행하고 작업에서 발생한 예외(성공이면 null) 반환
    private fun ExecutorService.start(task: () -> Any?): Future<Throwable?> =
        submit(Callable { runCatching { task() }.exceptionOrNull() })

    companion object {
        private const val ADMIN_USER_ID = 9831L
        private const val MEMBER_USER_ID = 9832L
        private const val OTHER_USER_ID = 9833L

        // 다른 요청을 기다리는 시간 (H2 잠금 대기 2초보다 짧게)
        private const val WAIT_MILLIS = 500L
    }
}
