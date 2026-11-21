package lk.dexlanka.erp.quality;

import jakarta.validation.Valid;
import java.util.Map;
import lk.dexlanka.erp.platform.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class QualityController {
    private final QualityService quality;

    QualityController(QualityService quality) {
        this.quality = quality;
    }

    @PostMapping("/quality/{id}/inspect")
    Map<String, Object> inspect(
            Authentication a,
            @PathVariable String id,
            @Valid @RequestBody QualityCommands.Inspect c,
            @RequestHeader("Idempotency-Key") String k) {
        return quality.inspect(Actor.of(a), id, c, k);
    }
}
