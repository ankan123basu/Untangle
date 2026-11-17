package dev.untangle.ticket.application;

import dev.untangle.ticket.domain.SlaPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TicketProperties.class)
class TicketConfig {

  /** Built once at startup; invalid SLA config (missing or non-positive target) fails fast. */
  @Bean
  SlaPolicy slaPolicy(TicketProperties properties) {
    return properties.slaPolicy();
  }
}
