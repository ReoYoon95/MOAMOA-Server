package com.teamani.moamoa.global.config

import com.teamani.moamoa.global.auth.CurrentUserIdArgumentResolver
import com.teamani.moamoa.global.auth.JwtTokenProvider
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// @WebMvcTest는 WebMvcConfigurer를 불러오지만 JwtTokenProvider는 불러오지 않으므로,
// @CurrentUserId를 쓰지 않는 Controller 테스트가 이 Bean 없이도 실행되도록 @Lazy로 주입
@Configuration
class WebConfig(
    @Lazy private val jwtTokenProvider: JwtTokenProvider
) : WebMvcConfigurer {

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(CurrentUserIdArgumentResolver(jwtTokenProvider))
    }
}
