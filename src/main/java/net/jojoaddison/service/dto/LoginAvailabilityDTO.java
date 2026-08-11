package net.jojoaddison.service.dto;

import java.io.Serializable;
import java.util.List;

/**
 * The answer to "can I register under this login?".
 *
 * <p>{@code login} echoes back the <em>normalised</em> form actually checked — trimmed and
 * lower-cased, because {@code User.setLogin} lower-cases before saving and a caller who typed
 * {@code JDoe} would otherwise be told {@code JDoe} is free and then find {@code jdoe} registered.
 * The client should display what it gets back, not what the user typed.
 *
 * <p>{@code suggestions} is empty whenever {@code available} is true. It is a convenience, never a
 * reservation: nothing is held, and a suggestion can be taken by someone else before the form is
 * submitted. Registration remains the only authority on whether a login is free.
 */
public class LoginAvailabilityDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String login;

    private boolean available;

    private List<String> suggestions;

    public LoginAvailabilityDTO() {
        // Empty constructor needed for Jackson.
    }

    public LoginAvailabilityDTO(String login, boolean available, List<String> suggestions) {
        this.login = login;
        this.available = available;
        this.suggestions = suggestions;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "LoginAvailabilityDTO{" +
            "login='" + login + '\'' +
            ", available=" + available +
            ", suggestions=" + suggestions +
            "}";
    }
}
