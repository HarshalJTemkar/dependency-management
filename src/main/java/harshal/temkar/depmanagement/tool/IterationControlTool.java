package harshal.temkar.depmanagement.tool;
import harshal.temkar.depmanagement.constants.Constants;
import harshal.temkar.depmanagement.domain.enums.IterationStatus;
import harshal.temkar.depmanagement.domain.model.IterationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
/** Tool for managing iteration pass lifecycle inside agent loops. @author Harshal Temkar */
@Component
public class IterationControlTool {
    private static final Logger log = LoggerFactory.getLogger(IterationControlTool.class);
    /** Start a new iteration pass. @param ctx current context @return updated context with IN_PROGRESS status */
    @Tool(description = "Start an iteration pass for an agent loop. Returns updated IterationContext.")
    public IterationContext startPass(final IterationContext ctx) {
        MDC.put(Constants.MDC_AGENT_NAME, ctx.agentName());
        MDC.put(Constants.MDC_ITERATION_PASS, String.valueOf(ctx.passNumber()));
        log.info("[correlationId={}][agent={}][pass={}] Starting iteration pass {}/{}", ctx.correlationId(), ctx.agentName(), ctx.passNumber(), ctx.passNumber(), ctx.maxPasses());
        return new IterationContext(ctx.agentName(), ctx.passNumber(), ctx.maxPasses(), IterationStatus.IN_PROGRESS, ctx.correlationId());
    }
    /** Complete an iteration pass successfully. @param ctx current context @return updated context with COMPLETED_SUCCESS */
    @Tool(description = "Mark an iteration pass as completed with success.")
    public IterationContext completePass(final IterationContext ctx) {
        log.info("[correlationId={}][agent={}][pass={}] Iteration pass completed", ctx.correlationId(), ctx.agentName(), ctx.passNumber());
        return new IterationContext(ctx.agentName(), ctx.passNumber(), ctx.maxPasses(), IterationStatus.COMPLETED_SUCCESS, ctx.correlationId());
    }
    /** Advance to next pass. @param ctx current context @return context with incremented passNumber */
    @Tool(description = "Advance iteration context to the next pass number.")
    public IterationContext nextPass(final IterationContext ctx) {
        if (!ctx.canContinue()) {
            log.warn("[correlationId={}][agent={}] Max iterations ({}) reached", ctx.correlationId(), ctx.agentName(), ctx.maxPasses());
            return new IterationContext(ctx.agentName(), ctx.passNumber(), ctx.maxPasses(), IterationStatus.COMPLETED_MAX_REACHED, ctx.correlationId());
        }
        return new IterationContext(ctx.agentName(), ctx.passNumber()+1, ctx.maxPasses(), IterationStatus.NOT_STARTED, ctx.correlationId());
    }
}
