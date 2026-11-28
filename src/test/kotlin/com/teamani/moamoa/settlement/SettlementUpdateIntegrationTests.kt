package com.teamani.moamoa.settlement

import com.teamani.moamoa.global.auth.JwtTokenProvider
import com.teamani.moamoa.global.exception.ApiException
import com.teamani.moamoa.global.exception.ErrorCode
import com.teamani.moamoa.meeting.entity.Meeting
import com.teamani.moamoa.meeting.repository.MeetingRepository
import com.teamani.moamoa.member.entity.Member
import com.teamani.moamoa.member.entity.MemberRole
import com.teamani.moamoa.member.repository.MemberRepository
import com.teamani.moamoa.member.service.MemberService
import com.teamani.moamoa.notice.dto.NoticeCreateRequest
import com.teamani.moamoa.notice.repository.NoticeRepository
import com.teamani.moamoa.notice.service.NoticeService
import com.teamani.moamoa.schedule.entity.Schedule
import com.teamani.moamoa.schedule.repository.ScheduleRepository
import com.teamani.moamoa.settlement.dto.RegularDuesUpdateRequest
import com.teamani.moamoa.settlement.dto.SettlementCreateRequest
import com.teamani.moamoa.settlement.dto.SettlementUpdateRequest
import com.teamani.moamoa.settlement.entity.CycleType
import com.teamani.moamoa.settlement.entity.PaymentStatus
import com.teamani.moamoa.settlement.entity.RegularDues
import com.teamani.moamoa.settlement.entity.Settlement
import com.teamani.moamoa.settlement.entity.SettlementMember
import com.teamani.moamoa.settlement.repository.RegularDuesRepository
import com.teamani.moamoa.settlement.repository.SettlementMemberRepository
import com.teamani.moamoa.settlement.repository.SettlementRepository
import com.teamani.moamoa.settlement.service.RegularDuesService
import com.teamani.moamoa.settlement.service.SettlementService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// 실제 트랜잭션·DB에서 정산 수정(4-11)과 정기 회비 수정의 이번 회차 반영(4-9) 검증
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettlementUpdateIntegrationTests : BehaviorSpec() {

    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider
    @Autowired lateinit var settlementService: SettlementService
    @Autowired lateinit var regularDuesService: RegularDuesService
    @Autowired lateinit var noticeService: NoticeService
    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var meetingRepository: MeetingRepository
    @Autowired lateinit var memberRepository: MemberRepository
    @Autowired lateinit var scheduleRepository: ScheduleRepository
    @Autowired lateinit var settlementRepository: SettlementRepository
    @Autowired lateinit var settlementMemberRepository: SettlementMemberRepository
    @Autowired lateinit var regularDuesRepository: RegularDuesRepository
    @Autowired lateinit var noticeRepository: NoticeRepository

    init {
        Given("공지가 연결된 일반 정산(3명, 30,000원)이 있을 때") {
            When("총액과 대상 인원을 바꾸면") {
                Then("1/N로 다시 나누고 빠진 구성원은 삭제·추가된 구성원은 미납으로 만들며, 기존 공지는 [변경] 표시 후 새 공지로 바뀐다") {
                    val m = savedMeeting()
                    val settlementId = createGeneral(m, listOf(m.admin, m.members[0], m.members[1]))
                    val oldNoticeId = settlementRepository.findById(settlementId).orElseThrow().noticeId!!

                    val response = settlementService.updateSettlement(
                        m.meetingId, m.adminUserId, settlementId,
                        SettlementUpdateRequest(totalAmount = Optional.of(40000), memberIds = Optional.of(listOf(m.admin, m.members[0], m.members[2])))
                    )

                    response.totalAmount shouldBe 40000
                    response.memberCount shouldBe 3
                    // 40,000 ÷ 3 = 13,333, 잔여 1원은 생성자(관리자) 부담
                    amounts(settlementId) shouldBe mapOf(m.admin to 13334, m.members[0] to 13333, m.members[2] to 13333)
                    settlementMemberRepository.findBySettlementIdAndMemberId(settlementId, m.members[2])?.status shouldBe PaymentStatus.UNPAID

                    val oldNotice = noticeRepository.findById(oldNoticeId).orElseThrow()
                    oldNotice.title shouldStartWith "[변경] "
                    oldNotice.content shouldStartWith "⚠ 이 정산은 변경되었습니다. 새 공지를 확인해 주세요.\n──── 기존 내용 ────\n"
                    response.noticeId shouldNotBe oldNoticeId
                    val newNotice = noticeRepository.findById(response.noticeId!!).orElseThrow()
                    newNotice.title shouldBe "[정산 변경] 회식비 (수정 ${today()})"
                    newNotice.content shouldStartWith "변경된 항목: 금액, 대상 인원\n정산명: 회식비\n기본 1인당 금액: 13,333원"
                }
            }

            When("정산명만 바꾸고 연결 일정을 null로 보내 해제하면 (HTTP)") {
                Then("공지는 그대로 두고 정산명·일정만 바뀐다") {
                    val m = savedMeeting()
                    val scheduleId = checkNotNull(
                        scheduleRepository.save(
                            Schedule(meetingId = m.meetingId, title = "정기 모임", startsAt = OffsetDateTime.parse("2099-10-11T19:00:00+09:00"))
                        ).id
                    )
                    val settlementId = createGeneral(m, listOf(m.admin, m.members[0]), scheduleId = scheduleId)
                    val noticeId = settlementRepository.findById(settlementId).orElseThrow().noticeId

                    mockMvc.patch("/v1/meetings/${m.meetingId}/settlements/$settlementId") {
                        header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtTokenProvider.createAccessToken(m.adminUserId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"title":"2차 회식비","scheduleId":null}"""
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.data.title") { value("2차 회식비") }
                        jsonPath("$.data.scheduleId") { value(null) }
                        jsonPath("$.data.noticeId") { value(noticeId!!.toInt()) }
                    }
                    noticeRepository.findById(noticeId!!).orElseThrow().title shouldBe "[정산] 회식비"
                }
            }

            When("필수 항목을 null로 보내거나 대상을 비워 보내면 (HTTP)") {
                Then("400 VALIDATION_ERROR이다") {
                    val m = savedMeeting()
                    val settlementId = createGeneral(m, listOf(m.admin, m.members[0]))
                    listOf("""{"title":null}""", """{"memberIds":[]}""", """{"dueAt":null}""").forEach { body ->
                        mockMvc.patch("/v1/meetings/${m.meetingId}/settlements/$settlementId") {
                            header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtTokenProvider.createAccessToken(m.adminUserId)}")
                            contentType = MediaType.APPLICATION_JSON
                            content = body
                        }.andExpect {
                            status { isBadRequest() }
                            jsonPath("$.error.code") { value("VALIDATION_ERROR") }
                        }
                    }
                }
            }
        }

        Given("납부를 제출한 구성원이 있는 일반 정산이 있을 때") {
            When("금액이나 대상 인원을 바꾸면") {
                Then("422이고, 반려되어 미납으로 돌아간 경우도 제출 기록으로 보고 422이다. 계좌·기한은 바꿀 수 있다") {
                    val m = savedMeeting()
                    val settlementId = createGeneral(m, listOf(m.admin, m.members[0]))
                    settlementService.submitPayment(m.meetingId, m.memberUserIds[0], settlementId)

                    errorOf { update(m, settlementId, SettlementUpdateRequest(totalAmount = Optional.of(50000))) } shouldBe ErrorCode.UNPROCESSABLE_ENTITY

                    val paymentId = checkNotNull(settlementMemberRepository.findBySettlementIdAndMemberId(settlementId, m.members[0])?.id)
                    settlementService.rejectPayment(m.meetingId, m.adminUserId, settlementId, paymentId, null)
                    errorOf {
                        update(m, settlementId, SettlementUpdateRequest(memberIds = Optional.of(listOf(m.admin))))
                    } shouldBe ErrorCode.UNPROCESSABLE_ENTITY

                    val response = update(
                        m, settlementId,
                        SettlementUpdateRequest(bankName = Optional.of("신한은행"), dueAt = Optional.of(OffsetDateTime.parse("2099-11-20T23:59:00+09:00")))
                    )
                    response.bankName shouldBe "신한은행"
                    noticeRepository.findById(response.noticeId!!).orElseThrow().content shouldStartWith "변경된 항목: 계좌, 납부 기한\n"
                }
            }
        }

        Given("대상 구성원 중 한 명이 모임을 나간 일반 정산이 있을 때") {
            When("수정 화면처럼 기존 대상 목록을 그대로 보내며 계좌를 바꾸면") {
                Then("나간 구성원은 다시 확인하지 않아 200이고 대상은 그대로, 계좌만 바뀐다") {
                    val m = savedMeeting()
                    val settlementId = createGeneral(m, listOf(m.admin, m.members[0], m.members[1]))
                    memberService.leaveMeeting(m.meetingId, m.memberUserIds[1])

                    val response = update(
                        m, settlementId,
                        SettlementUpdateRequest(
                            memberIds = Optional.of(listOf(m.admin, m.members[0], m.members[1])),
                            bankName = Optional.of("신한은행")
                        )
                    )

                    response.bankName shouldBe "신한은행"
                    amounts(settlementId).keys shouldBe setOf(m.admin, m.members[0], m.members[1])
                    // 새로 추가하는 구성원은 지금처럼 모임 구성원인지 확인
                    errorOf {
                        update(m, settlementId, SettlementUpdateRequest(memberIds = Optional.of(listOf(m.admin, m.members[0], m.members[1], 999_999L))))
                    } shouldBe ErrorCode.NOT_FOUND
                }
            }
        }

        Given("취소된 정산이 있을 때") {
            When("수정하면") {
                Then("422이다") {
                    val m = savedMeeting()
                    val settlementId = createGeneral(m, listOf(m.admin, m.members[0]))
                    settlementService.cancelSettlement(m.meetingId, m.adminUserId, settlementId, null)

                    errorOf { update(m, settlementId, SettlementUpdateRequest(title = Optional.of("새 이름"))) } shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                }
            }
        }

        Given("공지가 연결된 정기 회비 회차 정산(1인 10,000원, 2명)이 있을 때") {
            When("총액을 보내면") {
                Then("422이다") {
                    val m = savedMeeting()
                    val settlementId = savedPeriodSettlement(m, savedRegularDues(m), listOf(m.admin, m.members[0]))

                    errorOf { update(m, settlementId, SettlementUpdateRequest(totalAmount = Optional.of(30000))) } shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                }
            }

            When("대상 인원을 3명으로 바꾸면") {
                Then("1인 금액은 그대로이고 총액은 30,000원이 되며, 새 공지는 [회비 변경]이다") {
                    val m = savedMeeting()
                    val settlementId = savedPeriodSettlement(m, savedRegularDues(m), listOf(m.admin, m.members[0]))

                    val response = update(m, settlementId, SettlementUpdateRequest(memberIds = Optional.of(listOf(m.admin, m.members[0], m.members[1]))))

                    response.totalAmount shouldBe 30000
                    amounts(settlementId).values.toSet() shouldBe setOf(10000)
                    val newNotice = noticeRepository.findById(response.noticeId!!).orElseThrow()
                    newNotice.title shouldBe "[회비 변경] 월 회비 (수정 ${today()})"
                    newNotice.content shouldStartWith "변경된 항목: 대상 인원\n회비명: 월 회비\n1인당 금액: 10,000원"
                }
            }

            When("한 명이 납부를 제출한 뒤 아직 제출하지 않은 구성원을 빼거나 새 구성원을 넣으면") {
                Then("1인 금액이 고정이라 허용되고, 제출한 구성원을 빼는 것만 422이다") {
                    val m = savedMeeting()
                    val settlementId = savedPeriodSettlement(m, savedRegularDues(m), listOf(m.admin, m.members[0], m.members[1]))
                    settlementService.submitPayment(m.meetingId, m.memberUserIds[0], settlementId)

                    // 이번 달부터 안 나오는 구성원(members[1])을 빼고 새 구성원(members[2])을 넣음
                    val response = update(m, settlementId, SettlementUpdateRequest(memberIds = Optional.of(listOf(m.admin, m.members[0], m.members[2]))))
                    response.totalAmount shouldBe 30000
                    amounts(settlementId) shouldBe mapOf(m.admin to 10000, m.members[0] to 10000, m.members[2] to 10000)
                    settlementMemberRepository.findBySettlementIdAndMemberId(settlementId, m.members[0])?.status shouldBe PaymentStatus.PENDING

                    errorOf {
                        update(m, settlementId, SettlementUpdateRequest(memberIds = Optional.of(listOf(m.admin, m.members[2]))))
                    } shouldBe ErrorCode.UNPROCESSABLE_ENTITY
                }
            }

            When("정기 회비 수정(4-9)에서 금액을 12,000원으로 바꾸고 이번 회차 반영을 켜면") {
                Then("이번 회차 정산도 1인 12,000원·총액 24,000원이 되고 currentSettlementApplied는 true이다") {
                    val m = savedMeeting()
                    val regularDues = savedRegularDues(m)
                    val settlementId = savedPeriodSettlement(m, regularDues, listOf(m.admin, m.members[0]))

                    val response = regularDuesService.updateRegularDues(
                        m.meetingId, m.adminUserId, checkNotNull(regularDues.id),
                        RegularDuesUpdateRequest(amount = Optional.of(12000), applyToCurrentSettlement = true)
                    )

                    response.currentSettlementApplied shouldBe true
                    val settlement = settlementRepository.findById(settlementId).orElseThrow()
                    settlement.totalAmount shouldBe 24000
                    amounts(settlementId).values.toSet() shouldBe setOf(12000)
                    noticeRepository.findById(settlement.noticeId!!).orElseThrow().content shouldStartWith "변경된 항목: 금액\n"
                }
            }

            When("납부 제출 기록이 있는데 이번 회차 반영을 켜거나, 반영을 켜지 않으면") {
                Then("제출 기록이 있으면 false로 설정만 저장하고, 켜지 않으면 null이다") {
                    val m = savedMeeting()
                    val regularDues = savedRegularDues(m)
                    val settlementId = savedPeriodSettlement(m, regularDues, listOf(m.admin, m.members[0]))
                    settlementService.submitPayment(m.meetingId, m.memberUserIds[0], settlementId)
                    val regularDuesId = checkNotNull(regularDues.id)

                    regularDuesService.updateRegularDues(
                        m.meetingId, m.adminUserId, regularDuesId,
                        RegularDuesUpdateRequest(amount = Optional.of(12000), applyToCurrentSettlement = true)
                    ).currentSettlementApplied shouldBe false
                    regularDuesService.updateRegularDues(
                        m.meetingId, m.adminUserId, regularDuesId, RegularDuesUpdateRequest(amount = Optional.of(13000))
                    ).currentSettlementApplied.shouldBeNull()

                    regularDuesRepository.findById(regularDuesId).orElseThrow().amount shouldBe 13000
                    amounts(settlementId).values.toSet() shouldBe setOf(10000)
                }
            }
        }
    }

    private data class SavedMeeting(
        val meetingId: Long,
        val adminUserId: Long,
        val admin: Long,
        val memberUserIds: List<Long>,
        val members: List<Long>
    )

    // 관리자 1명과 구성원 3명이 있는 모임 (사용자 ID는 테스트마다 다르게)
    private fun savedMeeting(): SavedMeeting {
        val meetingId = checkNotNull(meetingRepository.save(Meeting(name = "모아모아", inviteCode = UUID.randomUUID().toString().take(8))).id)
        val adminUserId = nextUserId.incrementAndGet()
        val admin = checkNotNull(memberRepository.save(Member(meetingId = meetingId, userId = adminUserId, role = MemberRole.ADMIN)).id)
        val memberUserIds = List(3) { nextUserId.incrementAndGet() }
        val members = memberUserIds.map {
            checkNotNull(memberRepository.save(Member(meetingId = meetingId, userId = it, role = MemberRole.MEMBER)).id)
        }
        return SavedMeeting(meetingId, adminUserId, admin, memberUserIds, members)
    }

    private fun createGeneral(m: SavedMeeting, targets: List<Long>, scheduleId: Long? = null): Long =
        settlementService.createSettlement(
            m.meetingId,
            m.adminUserId,
            SettlementCreateRequest(
                scheduleId = scheduleId,
                title = "회식비",
                totalAmount = 30000,
                memberIds = targets,
                bankName = "국민은행",
                accountNumber = "123456789012",
                accountHolder = "김모아",
                dueAt = OffsetDateTime.parse("2099-10-20T23:59:00+09:00"),
                postNotice = true
            )
        ).settlementId

    // 매월 1일 청구, 1인 10,000원
    private fun savedRegularDues(m: SavedMeeting): RegularDues =
        regularDuesRepository.save(
            RegularDues(
                meetingId = m.meetingId,
                createdBy = m.admin,
                title = "월 회비",
                amount = 10000,
                cycleType = CycleType.MONTHLY,
                cycleDay = "1",
                sendTime = LocalTime.of(9, 0),
                dueDays = 7,
                bankName = "국민은행",
                accountNumber = "123456789012",
                accountHolder = "김모아",
                createdAt = OffsetDateTime.parse("2026-01-01T00:00:00+09:00")
            )
        )

    // 이번 달 회차 정산과 안내 공지
    private fun savedPeriodSettlement(m: SavedMeeting, regularDues: RegularDues, targets: List<Long>): Long {
        val noticeId = noticeService.createNoticeByMember(
            m.meetingId, m.admin, NoticeCreateRequest(title = "[회비] 월 회비", content = "회비 안내")
        ).noticeId
        val settlementId = checkNotNull(
            settlementRepository.save(
                Settlement(
                    meetingId = m.meetingId,
                    regularDuesId = regularDues.id,
                    noticeId = noticeId,
                    title = "월 회비",
                    totalAmount = 10000 * targets.size,
                    bankName = "국민은행",
                    accountNumber = "123456789012",
                    accountHolder = "김모아",
                    dueAt = OffsetDateTime.parse("2099-10-20T23:59:00+09:00"),
                    billingPeriod = OffsetDateTime.now(KST).toLocalDate().withDayOfMonth(1)
                )
            ).id
        )
        settlementMemberRepository.saveAll(targets.map { SettlementMember(settlementId = settlementId, memberId = it, amount = 10000) })
        return settlementId
    }

    private fun update(m: SavedMeeting, settlementId: Long, request: SettlementUpdateRequest) =
        settlementService.updateSettlement(m.meetingId, m.adminUserId, settlementId, request)

    private fun amounts(settlementId: Long): Map<Long, Int> =
        settlementMemberRepository.findAllBySettlementIdOrderByIdAsc(settlementId).associate { it.memberId to it.amount }

    private fun today(): String = OffsetDateTime.now(KST).format(DateTimeFormatter.ofPattern("MM.dd"))

    private fun errorOf(block: () -> Any?): ErrorCode = shouldThrow<ApiException> { block() }.errorCode

    companion object {
        private val KST: ZoneOffset = ZoneOffset.ofHours(9)
        private val nextUserId = AtomicLong(9_600_000L + (System.nanoTime() % 100_000L) * 10)
    }
}
