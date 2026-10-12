package com.kami.qqbot.BilibiliRelated;

/** 发送解析消息所需的视频信息，不包含播放地址或短链中的分享参数。 */
public final class BilibiliVideo {
    private final String title;
    private final String coverUrl;
    private final int coverWidth;
    private final int coverHeight;
    private final String uploaderName;
    private final long uploaderId;
    private final long likes;
    private final long comments;
    private final long favorites;
    private final long shares;

    public BilibiliVideo(String title, String coverUrl, int coverWidth, int coverHeight,
                         String uploaderName, long uploaderId, long likes, long comments,
                         long favorites, long shares) {
        this.title = title;
        this.coverUrl = coverUrl;
        this.coverWidth = coverWidth;
        this.coverHeight = coverHeight;
        this.uploaderName = uploaderName;
        this.uploaderId = uploaderId;
        this.likes = likes;
        this.comments = comments;
        this.favorites = favorites;
        this.shares = shares;
    }

    public String title() { return title; }
    public String coverUrl() { return coverUrl; }
    public int coverWidth() { return coverWidth; }
    public int coverHeight() { return coverHeight; }
    public String uploaderName() { return uploaderName; }
    public long uploaderId() { return uploaderId; }
    public long likes() { return likes; }
    public long comments() { return comments; }
    public long favorites() { return favorites; }
    public long shares() { return shares; }
}
