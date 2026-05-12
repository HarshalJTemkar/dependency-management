package harshal.temkar.depmanagement.tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
/** Tool: splits dependency list into fixed-size batches. @author Harshal Temkar */
@Component
public class BatchSplitterTool {
    private static final Logger log = LoggerFactory.getLogger(BatchSplitterTool.class);
    @Value("${app.ai.iterations.analysis.batch-size}")
    private int batchSize;
    /** Split items into batches. @param items flat list @return list of batches */
    @Tool(description = "Split a dependency list into fixed-size batches for LLM analysis.")
    public List<List<String>> splitIntoBatches(final List<String> items) {
        final List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            batches.add(new ArrayList<>(items.subList(i, Math.min(i+batchSize,items.size()))));
        }
        log.debug("BatchSplitter: {} items to {} batches", items.size(), batches.size());
        return batches;
    }
}