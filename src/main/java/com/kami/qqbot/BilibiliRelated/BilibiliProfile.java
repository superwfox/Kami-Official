package com.kami.qqbot.BilibiliRelated;

/** 主页资料；动态接口失败不影响昵称、头像和两项统计的展示。 */
public final class BilibiliProfile {
    private final long id;
    private final String name;
    private final String avatarUrl;
    private final long followers;
    private final long videos;
    private final String latestDynamicUrl;
    private final String dynamicError;

    public BilibiliProfile(long id, String name, String avatarUrl, long followers, long videos,
                           String latestDynamicUrl, String dynamicError) {
        this.id = id;
        this.name = name;
        this.avatarUrl = avatarUrl;
        this.followers = followers;
        this.videos = videos;
        this.latestDynamicUrl = latestDynamicUrl;
        this.dynamicError = dynamicError;
    }

    public long id() { return id; }
    public String name() { return name; }
    public String avatarUrl() { return avatarUrl; }
    public long followers() { return followers; }
    public long videos() { return videos; }
    public String latestDynamicUrl() { return latestDynamicUrl; }
    public String dynamicError() { return dynamicError; }
}
