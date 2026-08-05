package net.jojoaddison.domain;

import java.io.Serializable;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * A refresh token issued to a mobile client.
 *
 * <p>The token presented by the client is {@code "<id>.<secret>"}. Only the SHA-256 of the
 * secret half is stored, so a leaked database yields nothing usable — an attacker would still
 * need the plaintext secret, which exists only on the device.
 *
 * <p>Tokens form a <strong>family</strong>: every rotation mints a successor sharing the
 * {@code familyId}. The family is the unit of revocation, which is what makes replay detection
 * meaningful — presenting an already-rotated token kills the whole chain, not just that link.
 *
 * <p>Browsers never get one of these. {@code AuthenticateController} only issues a refresh token
 * when the caller identifies as a mobile client, so the web app's response is unchanged and no
 * dead rows accumulate for sessions that could not store them.
 *
 * @see net.jojoaddison.service.RefreshTokenService
 */
@Document(collection = "refresh_token")
public class RefreshToken implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The public half of the presented token — used to look the row up. */
    @Id
    private String id;

    /** SHA-256 (hex) of the secret half. The secret itself is never stored. */
    @Field("token_hash")
    private String tokenHash;

    /** Constant across a rotation chain. The unit of revocation. */
    @Field("family_id")
    private String familyId;

    /** The gateway login, i.e. the JWT {@code sub}. */
    @Field("login")
    private String login;

    /** {@code mobile-ios} / {@code mobile-android}. */
    @Field("client")
    private String client;

    /** App-generated, stable across launches. Lets one device replace its own session. */
    @Field("device_id")
    private String deviceId;

    /** Human-readable, for a "your signed-in devices" screen. */
    @Field("device_name")
    private String deviceName;

    @Field("issued_at")
    private Instant issuedAt;

    /**
     * When this row becomes garbage. A TTL index on this field lets Mongo reap expired rows with
     * no scheduler — see {@code RefreshTokenIndexInitializer}, which creates that index explicitly
     * because this project does not enable Spring Data's automatic index creation.
     */
    @Field("expires_at")
    private Instant expiresAt;

    /** Set when this token is exchanged. Non-null means any further use is a replay. */
    @Field("rotated_at")
    private Instant rotatedAt;

    @Field("replaced_by_id")
    private String replacedById;

    @Field("revoked_at")
    private Instant revokedAt;

    @Field("revoked_reason")
    private String revokedReason;

    @Field("last_used_at")
    private Instant lastUsedAt;

    @Field("last_used_ip")
    private String lastUsedIp;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getFamilyId() {
        return familyId;
    }

    public void setFamilyId(String familyId) {
        this.familyId = familyId;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
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

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
    }

    public void setRotatedAt(Instant rotatedAt) {
        this.rotatedAt = rotatedAt;
    }

    public String getReplacedById() {
        return replacedById;
    }

    public void setReplacedById(String replacedById) {
        this.replacedById = replacedById;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public void setRevokedReason(String revokedReason) {
        this.revokedReason = revokedReason;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public String getLastUsedIp() {
        return lastUsedIp;
    }

    public void setLastUsedIp(String lastUsedIp) {
        this.lastUsedIp = lastUsedIp;
    }

    /** Usable = not revoked, not already exchanged, not expired. */
    public boolean isUsableAt(Instant now) {
        return revokedAt == null && rotatedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }

    // prettier-ignore
    @Override
    public String toString() {
        return "RefreshToken{" +
            "id='" + id + '\'' +
            ", familyId='" + familyId + '\'' +
            ", login='" + login + '\'' +
            ", client='" + client + '\'' +
            ", issuedAt=" + issuedAt +
            ", expiresAt=" + expiresAt +
            ", rotatedAt=" + rotatedAt +
            ", revokedAt=" + revokedAt +
            ", revokedReason='" + revokedReason + '\'' +
            '}';
    }
}
