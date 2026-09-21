package com.meterly.notification.config;

import com.meterly.notification.email.EmailSender;
import com.meterly.notification.email.LoggingEmailSender;
import com.meterly.notification.email.SmtpEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** Selects the {@link EmailSender} implementation from {@code meterly.notification.email.provider}. */
@Configuration(proxyBeanMethods = false)
public class EmailConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EmailConfiguration.class);

    @Bean
    public EmailSender emailSender(NotificationProperties properties, ObjectProvider<JavaMailSender> mailSender) {
        NotificationProperties.Email email = properties.email();
        log.info("Email provider: {} (from {})", email.provider(), email.from());
        return switch (email.provider()) {
            case LOGGING -> new LoggingEmailSender(email.from());
            case SMTP -> new SmtpEmailSender(mailSender.getObject(), email.from());
        };
    }
}
