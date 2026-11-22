package com.relay.coordination.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
public class SecurityConfiguration {
  @Bean
  @Profile({"dev", "test"})
  UserDetailsService localUsers(
      @Value("${relay.security.operator-password}") String operatorPassword,
      @Value("${relay.security.agent-password}") String agentPassword) {
    var encoder = new BCryptPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername("operator")
            .password("{bcrypt}" + encoder.encode(operatorPassword))
            .roles("ADMIN", "USER")
            .build(),
        User.withUsername("agent")
            .password("{bcrypt}" + encoder.encode(agentPassword))
            .authorities("ROLE_AGENT", "SCOPE_relay.read", "SCOPE_relay.write")
            .build());
  }

  @Bean
  @Order(1)
  @Profile({"dev", "test"})
  SecurityFilterChain localApi(HttpSecurity http) throws Exception {
    apiRules(http);
    return http.httpBasic(Customizer.withDefaults()).build();
  }

  @Bean
  @Order(1)
  @Profile("prod")
  SecurityFilterChain productionApi(HttpSecurity http) throws Exception {
    apiRules(http);
    var scopes = new JwtGrantedAuthoritiesConverter();
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Set<GrantedAuthority> authorities = new HashSet<>(scopes.convert(jwt));
          authorities.addAll(roleAuthorities(jwt.getClaim("relay_roles")));
          return authorities;
        });
    return http.oauth2ResourceServer(
            oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
        .build();
  }

  private void apiRules(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/**", "/mcp", "/actuator/**")
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/mcp")
                    .authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/**")
                    .hasAnyAuthority("ROLE_ADMIN", "SCOPE_relay.read")
                    .requestMatchers("/api/**")
                    .hasAnyAuthority("ROLE_ADMIN", "SCOPE_relay.write")
                    .anyRequest()
                    .denyAll());
    headers(http);
  }

  @Bean
  @Order(2)
  @Profile({"dev", "test"})
  SecurityFilterChain localWeb(HttpSecurity http) throws Exception {
    webRules(http);
    return http.formLogin(Customizer.withDefaults()).build();
  }

  @Bean
  @Order(2)
  @Profile("prod")
  SecurityFilterChain productionWeb(HttpSecurity http) throws Exception {
    webRules(http);
    var oidcUsers = new OidcUserService();
    return http.oauth2Login(
            oauth ->
                oauth.userInfoEndpoint(
                    userInfo ->
                        userInfo.oidcUserService(
                            request -> {
                              var user = oidcUsers.loadUser(request);
                              Set<GrantedAuthority> authorities =
                                  new HashSet<>(user.getAuthorities());
                              authorities.addAll(roleAuthorities(user.getClaim("relay_roles")));
                              return new DefaultOidcUser(
                                  authorities, user.getIdToken(), user.getUserInfo(), "sub");
                            })))
        .build();
  }

  private void webRules(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
        auth ->
            auth.requestMatchers(
                    "/css/**",
                    "/images/**",
                    "/favicon.svg",
                    "/error",
                    "/login",
                    "/oauth2/**",
                    "/login/oauth2/**")
                .permitAll()
                .requestMatchers(HttpMethod.POST, "/ui/**")
                .hasRole("ADMIN")
                .anyRequest()
                .authenticated());
    headers(http);
  }

  private void headers(HttpSecurity http) throws Exception {
    http.headers(
        headers ->
            headers
                .contentSecurityPolicy(
                    csp ->
                        csp.policyDirectives(
                            "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'none'; img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'"))
                .referrerPolicy(
                    ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));
  }

  private static Set<GrantedAuthority> roleAuthorities(Object claim) {
    Set<GrantedAuthority> roles = new HashSet<>();
    if (claim instanceof Collection<?> values)
      for (Object value : values) {
        if (Set.of("ADMIN", "USER", "AGENT").contains(value))
          roles.add(new SimpleGrantedAuthority("ROLE_" + value));
      }
    return roles;
  }
}
