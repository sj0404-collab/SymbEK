package dev.symbiosis.kenji;

/** One ROM on disk or behind a SAF URI. */
public final class GameItem {
    public final String title;
    public final String path;
    public final String titleId;
    public final String size;
    public final String fileName;

    public GameItem(String title, String path, String titleId, String size, String fileName) {
        this.title = title == null ? "" : title;
        this.path = path == null ? "" : path;
        this.titleId = titleId == null ? "" : titleId;
        this.size = size == null ? "" : size;
        this.fileName = fileName == null ? "" : fileName;
    }

    public String key() {
        return path.isEmpty() ? fileName : path;
    }
}
