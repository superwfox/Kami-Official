package com.kami.qqbot.BilibiliRelated;

/** QQ 原生 Markdown：封面、二级标题、带 UP 主页链接的引用、四项统计。 */
public final class BilibiliMessageFormatter {
    private BilibiliMessageFormatter() {}

    public static String markdown(BilibiliVideo video) {
        String imageUrl = video.coverUrl().replace("(", "%28").replace(")", "%29");
        return "![视频封面 #" + video.coverWidth() + "px #" + video.coverHeight() + "px](" + imageUrl + ")\n\n"
                + "## " + escapeInline(video.title()) + "\n"
                + "> UP : [" + escapeInline(video.uploaderName()) + "](https://space.bilibili.com/"
                + video.uploaderId() + ")\n\n"
                + "## 赞：" + CompactNumberFormatter.format(video.likes())
                + " 评论：" + CompactNumberFormatter.format(video.comments())
                + " 收藏：" + CompactNumberFormatter.format(video.favorites())
                + " 分享：" + CompactNumberFormatter.format(video.shares());
    }

    /** 主页使用小尺寸方形头像，昵称链接到主页；动态失败时仍展示主页资料。 */
    public static String profileMarkdown(BilibiliProfile profile) {
        String avatarUrl = profile.avatarUrl().replace("(", "%28").replace(")", "%29");
        String homepage = "https://space.bilibili.com/" + profile.id();
        String dynamic;
        if (profile.latestDynamicUrl() != null) {
            dynamic = "[最新动态](" + profile.latestDynamicUrl() + ")";
        } else if (profile.dynamicError() != null) {
            dynamic = "最新动态：暂时无法获取\n\n[查看主页动态](" + homepage + "/dynamic)";
        } else {
            dynamic = "最新动态：暂无公开动态";
        }
        return "![头像 #128px #128px](" + avatarUrl + ")\n\n"
                + "## [" + escapeInline(profile.name()) + "](" + homepage + ")\n\n"
                + "## 粉丝：" + CompactNumberFormatter.format(profile.followers())
                + " 上传视频：" + CompactNumberFormatter.format(profile.videos()) + "\n\n" + dynamic;
    }

    /** 标题、昵称中的换行及 Markdown 符号不能改变消息结构或链接目标。 */
    private static String escapeInline(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch) || ch == '\u2028' || ch == '\u2029') {
                escaped.append(' ');
            } else {
                if ("\\`*_{}[]()#+-.!>|~<&".indexOf(ch) >= 0) escaped.append('\\');
                escaped.append(ch);
            }
        }
        return escaped.toString();
    }
}
