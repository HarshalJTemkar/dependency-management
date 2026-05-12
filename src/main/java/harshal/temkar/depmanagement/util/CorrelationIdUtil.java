package harshal.temkar.depmanagement.util;
import harshal.temkar.depmanagement.constants.Constants;
import org.slf4j.MDC;
import java.util.Optional;
import java.util.UUID;
/** Static utility for working with MDC correlation IDs. @author Harshal Temkar */
public final class CorrelationIdUtil {
    private CorrelationIdUtil(){}
    /** Returns current correlationId from MDC, or generates a new UUID if absent. @return correlationId string */
    public static String current(){
        return Optional.ofNullable(MDC.get(Constants.MDC_CORRELATION_ID)).filter(s->!s.isBlank()).orElse(UUID.randomUUID().toString());
    }
    /** Sets the correlationId in MDC. @param id correlation ID to set */
    public static void set(final String id){MDC.put(Constants.MDC_CORRELATION_ID,id);}
    /** Clears correlationId from MDC. */
    public static void clear(){MDC.remove(Constants.MDC_CORRELATION_ID);}
    /** Returns the given id if non-blank, otherwise generates a new UUID. @param id candidate id @return resolved id */
    public static String resolveOrGenerate(final String id){
        return (id!=null&&!id.isBlank())?id:UUID.randomUUID().toString();
    }
}