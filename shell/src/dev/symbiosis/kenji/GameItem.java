package dev.symbiosis.kenji;

public final class GameItem {
    public final String title;
    public final String path;
    public final String titleId;
    public final String size;
    public final String fileName;
    public final boolean update;
    public final long bytes;

    public GameItem(String title, String path, String titleId, String size, String fileName) {
        this(title, path, titleId, size, fileName, FolderStore.isUpdateId(titleId), 0L);
    }

    public GameItem(String title, String path, String titleId, String size, String fileName,
                    boolean update, long bytes) {
        this.title = title == null ? "" : title;
        this.path = path == null ? "" : path;
        this.titleId = titleId == null ? "" : titleId;
        this.size = size == null ? "" : size;
        this.fileName = fileName == null ? "" : fileName;
        this.update = update;
        this.bytes = bytes;
    }

    public String key() {
        return path.isEmpty() ? fileName : path;
    }
}
