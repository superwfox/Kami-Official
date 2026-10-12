package com.kami.qqbot.BilibiliRelated;

/** 视频资料与官方独立播放器入口，不保存分享追踪参数。 */
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
    private final String playerUrl;

    public BilibiliVideo(String title, String coverUrl, int coverWidth, int coverHeight,
                         String uploaderName, long uploaderId, long likes, long comments,
                         long favorites, long shares, String playerUrl) {
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
        this.playerUrl = playerUrl;
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
    public String playerUrl() { return playerUrl; }
}
