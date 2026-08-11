package net.jojoaddison.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import net.jojoaddison.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.jhipster.config.JHipsterProperties;

/**
 * Service for sending emails asynchronously.
 */
@Service
public class MailService {

    private final Logger log = LoggerFactory.getLogger(MailService.class);

    private static final String USER = "user";

    private static final String BASE_URL = "baseUrl";

    private final JHipsterProperties jHipsterProperties;

    private final JavaMailSender javaMailSender;

    private final MessageSource messageSource;

    private final SpringTemplateEngine templateEngine;

    public MailService(
        JHipsterProperties jHipsterProperties,
        JavaMailSender javaMailSender,
        MessageSource messageSource,
        SpringTemplateEngine templateEngine
    ) {
        this.jHipsterProperties = jHipsterProperties;
        this.javaMailSender = javaMailSender;
        this.messageSource = messageSource;
        this.templateEngine = templateEngine;
    }

    /**
     * Sends an email off the calling thread and returns immediately.
     *
     * <p>The {@code subscribeOn} is the whole point of this method and must not be removed. JavaMail is blocking, and
     * every caller here is a reactive handler running on a Netty event loop; without it the {@code Mono.defer} below
     * runs the SMTP conversation on the subscribing thread — the event loop — for as long as the relay takes. Measured
     * against a real relay in the sibling hc-patient gateway, which carried this identical code:
     * <strong>2.8 seconds on an event loop thread</strong>, during which every other request assigned to that thread
     * waits. It reads as asynchronous and is not.</p>
     *
     * <p>BlockHound does not catch this. It fails blocking calls on non-blocking threads, but {@code MailServiceIT}
     * mocks {@link JavaMailSender}, so no socket is ever opened during the tests and nothing blocks.
     * {@code testSendEmailRunsOffTheCallingThread} guards it instead.</p>
     */
    public void sendEmail(String to, String subject, String content, boolean isMultipart, boolean isHtml) {
        Mono.defer(() -> {
            this.sendEmailSync(to, subject, content, isMultipart, isHtml);
            return Mono.empty();
        })
            .subscribeOn(Schedulers.boundedElastic())
            // Fire-and-forget, so nothing downstream would ever see a failure: sendEmailSync already logs the mail
            // failures it expects, and this handles the rest rather than letting Reactor drop them silently.
            .subscribe(null, e -> log.warn("Email to '{}' failed unexpectedly", maskEmail(to), e));
    }

    /**
     * Reduces an address to {@code j***@example.com} for logging.
     *
     * <p>These logs are shipped off the host, so a full address in one outlives the incident it was
     * written for. The domain is kept because it is what a relay problem shows up in — "every
     * failure is @nhs.net" is a diagnosis, "every failure is to a user" is not. Where a login is on
     * hand it is preferred over this entirely, being both a better identifier and not personal data.
     */
    static String maskEmail(String address) {
        if (address == null || address.isBlank()) {
            return "(none)";
        }
        int at = address.indexOf('@');
        if (at < 1) {
            // No local part to keep, so keep nothing: a bare domain or a malformed value.
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }

    private void sendEmailSync(String to, String subject, String content, boolean isMultipart, boolean isHtml) {
        log.debug(
            "Send email[multipart '{}' and html '{}'] to '{}' with subject '{}' and content={}",
            isMultipart,
            isHtml,
            to,
            subject,
            content
        );

        // Prepare message using a Spring helper
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        try {
            MimeMessageHelper message = new MimeMessageHelper(mimeMessage, isMultipart, StandardCharsets.UTF_8.name());
            message.setTo(to);
            message.setFrom(jHipsterProperties.getMail().getFrom());
            message.setSubject(subject);
            message.setText(content, isHtml);
            javaMailSender.send(mimeMessage);
            // INFO, not DEBUG. This is the only record that a message actually left for a given
            // user, and production runs net.jojoaddison at INFO — so at DEBUG a registration was
            // entirely invisible in the logs and "I never received the activation email" could not
            // be answered either way. The subject distinguishes activation from reset without
            // adding anything sensitive.
            log.info("Sent email to '{}' with subject '{}'", maskEmail(to), subject);
        } catch (MailException | MessagingException e) {
            log.warn("Email could not be sent to user '{}'", maskEmail(to), e);
        }
    }

    /**
     * Renders a template and sends it off the calling thread. See {@link #sendEmail} for why the scheduler matters:
     * this path additionally renders Thymeleaf and resolves a message bundle, so it does even more work than the send
     * itself before it reaches the relay.
     */
    public void sendEmailFromTemplate(User user, String templateName, String titleKey) {
        Mono.defer(() -> {
            this.sendEmailFromTemplateSync(user, templateName, titleKey);
            return Mono.empty();
        })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(null, e -> log.warn("Email to user '{}' failed unexpectedly", user.getLogin(), e));
    }

    private void sendEmailFromTemplateSync(User user, String templateName, String titleKey) {
        if (user.getEmail() == null) {
            log.debug("Email doesn't exist for user '{}'", user.getLogin());
            return;
        }
        Locale locale = Locale.forLanguageTag(user.getLangKey());
        Context context = new Context(locale);
        context.setVariable(USER, user);
        context.setVariable(BASE_URL, jHipsterProperties.getMail().getBaseUrl());
        String content = templateEngine.process(templateName, context);
        String subject = messageSource.getMessage(titleKey, null, locale);
        this.sendEmailSync(user.getEmail(), subject, content, false, true);
    }

    public void sendActivationEmail(User user) {
        log.info("Sending activation email to user '{}'", user.getLogin());
        this.sendEmailFromTemplate(user, "mail/activationEmail", "email.activation.title");
    }

    public void sendCreationEmail(User user) {
        log.info("Sending creation email to user '{}'", user.getLogin());
        this.sendEmailFromTemplate(user, "mail/creationEmail", "email.activation.title");
    }

    public void sendPasswordResetMail(User user) {
        log.info("Sending password reset email to user '{}'", user.getLogin());
        this.sendEmailFromTemplate(user, "mail/passwordResetEmail", "email.reset.title");
    }
}
