package com.teamani.moamoa.schedule.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.FutureOrPresent
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import jakarta.validation.valueextraction.Unwrapping
import java.time.OffsetDateTime
import java.util.Optional

// 종료 일시가 시작 일시 이후인지는 수정 API와 같은 규칙으로 Service에서 검증
data class ScheduleCreateRequest(
    @field:NotBlank(message = "일정 제목은 필수입니다.")
    @field:Size(max = 50, message = "제목은 50자 이하여야 합니다.")
    val title: String?,

    val description: String? = null,

    @field:NotNull(message = "시작 일시는 필수입니다.")
    @field:FutureOrPresent(message = "시작 일시는 현재 시각 이후여야 합니다.")
    val startAt: OffsetDateTime?,

    val endAt: OffsetDateTime? = null,

    @field:Size(max = 255, message = "장소는 255자 이하여야 합니다.")
    val location: String? = null
)

data class ScheduleCreateResponse(
    val scheduleId: Long,
    val title: String,
    val startAt: OffsetDateTime
)

data class ScheduleListResponse(
    val schedules: List<ScheduleSummaryResponse>
)

data class ScheduleSummaryResponse(
    val scheduleId: Long,
    val title: String,
    val startAt: OffsetDateTime,
    val endAt: OffsetDateTime?,
    val location: String?
)

// 부분 수정: 보내지 않은 필드는 변경하지 않음 (종료 일시 순서는 기존 값과 합친 결과로 Service에서 검증)
// 미전송(null)과 null 전송(Optional.empty)을 구분해 선택 항목(description, endAt, location)은 값을 비우고,
// 필수 항목(title, startAt)은 VALIDATION_ERROR로 거부
// Optional 안의 값을 검증하도록 제약에 Unwrap 지정
data class ScheduleUpdateRequest(
    @field:Pattern(regexp = "(?s).*\\S.*", message = "일정 제목은 공백일 수 없습니다.", payload = [Unwrapping.Unwrap::class])
    @field:Size(max = 50, message = "제목은 50자 이하여야 합니다.", payload = [Unwrapping.Unwrap::class])
    val title: Optional<String>? = null,

    val description: Optional<String>? = null,

    // 보낸 경우에만 검증하므로 지난 일정의 다른 항목 수정은 허용
    @field:FutureOrPresent(message = "시작 일시는 현재 시각 이후여야 합니다.", payload = [Unwrapping.Unwrap::class])
    val startAt: Optional<OffsetDateTime>? = null,

    val endAt: Optional<OffsetDateTime>? = null,

    @field:Size(max = 255, message = "장소는 255자 이하여야 합니다.", payload = [Unwrapping.Unwrap::class])
    val location: Optional<String>? = null
) {
    // 필수 항목은 null로 비울 수 없음
    @get:JsonIgnore
    @get:AssertTrue(message = "일정 제목은 비울 수 없습니다.")
    val isTitleNotCleared: Boolean
        get() = title?.isPresent ?: true

    @get:JsonIgnore
    @get:AssertTrue(message = "시작 일시는 비울 수 없습니다.")
    val isStartAtNotCleared: Boolean
        get() = startAt?.isPresent ?: true
}

data class ScheduleDeleteResponse(
    val scheduleId: Long
)

data class ScheduleDetailResponse(
    val scheduleId: Long,
    val title: String,
    val description: String?,
    val startAt: OffsetDateTime,
    val endAt: OffsetDateTime?,
    val location: String?
)
