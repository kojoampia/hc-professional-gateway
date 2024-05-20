package net.jojoaddison.service;

import io.micrometer.core.annotation.Timed;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient("hcprofessionalservice")
public interface ProfileGateway {
    @PostMapping("/api/profile")
    @Timed(value = "register.professional")
    public void createProfile(@RequestBody Map<String, Object> profile);
}
