package harshal.temkar.depmanagement.domain.enums;

/** Input source type for dependency analysis. @author Harshal Temkar */
public enum SourceType {
    /** Public GitHub repository - no auth required. */
    GITHUB_PUBLIC,
    /** Private GitHub repository - requires PAT token. */
    GITHUB_PRIVATE,
    /** Local file system path. */
    LOCAL
}
