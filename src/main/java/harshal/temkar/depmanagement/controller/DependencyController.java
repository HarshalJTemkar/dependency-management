package harshal.temkar.depmanagement.controller;

import harshal.temkar.depmanagement.agent.OrchestratorAgent;
import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.dto.AnalysisRequestDTO;
import harshal.temkar.depmanagement.dto.DependencyReportDTO;
import harshal.temkar.depmanagement.util.CorrelationIdUtil;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Optional;

/**
 * Spring MVC controller for the Dependency Management UI.
 *
 * <p>Exposes two endpoints:</p>
 * <ul>
 *   <li>GET  {@code /}         — renders the input form</li>
 *   <li>POST {@code /analyze}  — triggers the analysis pipeline and renders the report</li>
 * </ul>
 *
 * <p>No business logic lives here — all orchestration is delegated to
 * {@link OrchestratorAgent}.</p>
 *
 * @author Harshal Temkar
 */
@Controller
@RequestMapping("/")
public class DependencyController {

    private static final Logger log = LoggerFactory.getLogger(DependencyController.class);

    private final OrchestratorAgent orchestratorAgent;

    /**
     * Constructs the controller with the master orchestrator agent.
     *
     * @param orchestratorAgent master pipeline coordinator
     */
    public DependencyController(final OrchestratorAgent orchestratorAgent) {
        this.orchestratorAgent = orchestratorAgent;
    }

    /**
     * Renders the analysis input form.
     *
     * @param model Spring MVC model for Thymeleaf
     * @return logical view name for the input form
     */
    @GetMapping
    public String showForm(final Model model) {
        model.addAttribute("request", new AnalysisRequestDTO(null, null, null, null, "en", null));
        log.debug("[correlationId={}] Rendering input form", CorrelationIdUtil.current());
        return "index";
    }

    /**
     * Accepts a dependency analysis request, runs the full pipeline, and renders the report.
     *
     * @param request          validated form data from the user
     * @param correlationHeader optional {@code X-Correlation-ID} header from the client
     * @param model            Spring MVC model for Thymeleaf
     * @return logical view name for the report page
     */
    @PostMapping("analyze")
    public String analyze(@Valid @ModelAttribute final AnalysisRequestDTO request,
                           @RequestHeader(value = Constants.CORRELATION_ID_HEADER, required = false)
                           final String correlationHeader,
                           final Model model) {
        final String correlationId = Optional.ofNullable(correlationHeader)
                .filter(h -> !h.isBlank())
                .orElse(Optional.ofNullable(request.correlationId())
                        .filter(c -> !c.isBlank())
                        .orElse(java.util.UUID.randomUUID().toString()));

        MDC.put(Constants.MDC_CORRELATION_ID, correlationId);
        log.info("[correlationId={}] POST /analyze — sourceType={}", correlationId, request.sourceType());

        try {
            // Rebuild request with resolved correlationId
            final AnalysisRequestDTO enriched = new AnalysisRequestDTO(
                    request.sourceType(),
                    request.repoUrl(),
                    request.localPath(),
                    request.githubToken(),
                    request.language() != null ? request.language() : "en",
                    correlationId
            );

            final DependencyReportDTO report = orchestratorAgent.orchestrate(enriched);
            model.addAttribute("report", report);
            model.addAttribute("lang", enriched.language());
            log.info("[correlationId={}] Analysis complete — {} deps", correlationId, report.totalDependencies());
            return "report";
        } finally {
            MDC.clear();
        }
    }
}
