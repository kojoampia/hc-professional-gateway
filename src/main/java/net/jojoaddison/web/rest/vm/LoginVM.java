package net.jojoaddison.web.rest.vm;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * View Model object for storing a user's credentials.
 */
public class LoginVM {

    @NotNull
    @Size(min = 1, max = 50)
    private String username;

    @NotNull
    @Size(min = 4, max = 100)
    private String password;

    private boolean rememberMe;

    /**
     * Identifies a mobile client, e.g. {@code mobile-ios} / {@code mobile-android}.
     *
     * <p>Optional and absent for browsers. Its presence is what makes the gateway mint a refresh
     * token and a short-lived access token instead of the classic 24 h / 30 d one, so a browser's
     * response is byte-identical to what it has always been.
     */
    private String client;

    /** App-generated device identifier, stable across launches. Optional. */
    private String deviceId;

    /** Human-readable device name for the "signed-in devices" list. Optional. */
    private String deviceName;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isRememberMe() {
        return rememberMe;
    }

    public void setRememberMe(boolean rememberMe) {
        this.rememberMe = rememberMe;
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        this.client = client;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    /**
     * True when this login came from a mobile app rather than a browser.
     *
     * <p>{@code "web"} is treated as a browser even when sent explicitly, so a misconfigured web
     * build cannot accidentally start accumulating refresh-token rows it can never use.
     */
    public boolean isMobileClient() {
        return client != null && !client.isBlank() && !"web".equalsIgnoreCase(client.trim());
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "LoginVM{" +
            "username='" + username + '\'' +
            ", rememberMe=" + rememberMe +
            '}';
    }
}
