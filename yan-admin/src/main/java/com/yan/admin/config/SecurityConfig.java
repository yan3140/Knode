package com.yan.admin.config;


import com.yan.admin.filter.JwtAuthenticationTokenFilter;
import com.yan.constants.AuthConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private AuthenticationEntryPoint authenticationEntryPoint;

    @Autowired
    private AccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthenticationTokenFilter authenticationTokenFilter) {
        this.authenticationTokenFilter = authenticationTokenFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private final JwtAuthenticationTokenFilter authenticationTokenFilter;


    @Bean
    public AuthenticationManager AuthenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();

    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 关闭 CSRF（若为前后端分离场景，通常需要关闭）
                .csrf(AbstractHttpConfigurer::disable)
                // 配置 Session 为无状态（不通过 Session 存储 SecurityContext）
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                // 配置请求授权规则
                .authorizeHttpRequests(auth -> auth
                                .requestMatchers(HttpMethod.POST,
                                        "/login", "/user/login", "/user/register",
                                        "/api/blog/auth/login", "/api/blog/user/register",
                                        "/api/admin/auth/login").permitAll()
                                .requestMatchers(HttpMethod.GET,
                                        "/actuator/health", "/actuator/health/**", "/actuator/info",
                                        "/article/**", "/category/**", "/link/**", "/comment/**",
                                        "/api/blog/article/**", "/api/blog/category/**",
                                        "/api/blog/link/**", "/api/blog/comment/**",
                                        "/user/profile/**", "/api/blog/user/profile/**",
                                        "/tag/**", "/api/blog/tag/**",
                                        "/follow/*/following", "/api/blog/follow/*/following",
                                        "/search/**", "/api/blog/search/**").permitAll()
                                .requestMatchers(HttpMethod.PUT,
                                        "/article/updateViewCount/**", "/api/blog/article/updateViewCount/**").permitAll()
                                .requestMatchers(
                                        "/actuator/**",
                                        "/api/admin/**", "/system/**", "/content/**",
                                        "/getInfo", "/getRouters", "/upload", "/user/logout")
                                .hasAuthority(AuthConstants.ADMIN_AUTHORITY)
                                .anyRequest().authenticated()
                )
                .logout(AbstractHttpConfigurer::disable)
                //允许跨域
                .cors(Customizer.withDefaults());

        http.addFilterBefore(authenticationTokenFilter, UsernamePasswordAuthenticationFilter.class);
        http.exceptionHandling(e ->
                e.authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();

    }


}
