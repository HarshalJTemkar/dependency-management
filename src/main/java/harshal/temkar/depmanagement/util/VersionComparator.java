package harshal.temkar.depmanagement.util;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.stream.IntStream;
/**
 * Utility component for semantic version comparison and diff calculation.
 * Parses versions in MAJOR.MINOR.PATCH format, tolerating missing segments.
 * @author Harshal Temkar
 */
@Component
public class VersionComparator {
    /**
     * Compares two semantic version strings.
 * @param v1 first version
     * @param v2 second version
     * @return negative if v1 < v2, 0 if equal, positive if v1 > v2
     */
    public int compare(final String v1, final String v2) {
        final int[] a = parse(v1);
        final int[] b = parse(v2);
        return IntStream.range(0,3).map(i->Integer.compare(a[i],b[i])).filter(c->c!=0).findFirst().orElse(0);
    }
    /**
     * Calculates major version difference.
 * @param current current version
     * @param latest latest version
     * @return major segments behind (0 if up-to-date)
     */
    public int majorDiff(final String current, final String latest){
        return Math.max(0,parse(latest)[0]-parse(current)[0]);
    }
    /**
     * Calculates minor version difference.
 * @param current current
     * @param latest  latest
     * @return minor segments behind
     */
    public int minorDiff(final String current,final String latest){
        return Math.max(0,parse(latest)[1]-parse(current)[1]);
    }
    /**
     * Calculates patch version difference.
 * @param current current
     * @param latest  latest
     * @return patch segments behind
     */
    public int patchDiff(final String current,final String latest){
        return Math.max(0,parse(latest)[2]-parse(current)[2]);
    }
    private int[] parse(final String version){
        if(version==null||version.isBlank()||"UNKNOWN".equalsIgnoreCase(version)){return new int[]{0,0,0};}
        final String[] parts=version.split("[.\\\\-]");
        return IntStream.range(0,3).map(i->{
            if(i>=parts.length)return 0;
            try{return Integer.parseInt(parts[i].replaceAll("[^0-9]",""));}catch(NumberFormatException e){return 0;}
        }).toArray();
    }
}