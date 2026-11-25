package com.wuyao.growth.library;

import com.wuyao.growth.creative.image.ImageDtos;
import com.wuyao.growth.video.VideoDtos;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class LibraryDtos {
    private LibraryDtos() {
    }

    public record FolderRequest(@NotBlank(message = "请填写文件夹名称") @Size(max = 30, message = "文件夹名称最多 30 个字") String name) {
    }

    public record Folder(Long id, String name, long workCount, Instant createdAt) {
    }

    /** 作品库首页：文件夹列表和根目录里的作品数。 */
    public record Folders(List<Folder> folders, long rootCount) {
    }

    /** folderId 为空表示保存到根目录。已经保存过的作品再保存，等于移到这个位置。 */
    public record SaveRequest(@NotBlank @Pattern(regexp = "IMAGE|VIDEO", message = "作品类型不合法") String kind,
                              @NotNull Long sourceId, Long folderId) {
    }

    public record MoveRequest(Long folderId) {
    }

    /** image 和 video 只有一个有值，与原来的作品接口字段一致。 */
    public record Work(Long id, String kind, Long sourceId, Long folderId, Instant savedAt,
                       ImageDtos.Work image, VideoDtos.History video) {
    }

    /** 生成页查「这件作品存了没有、存在哪」。 */
    public record Saved(Long sourceId, Long workId, Long folderId, String folderName) {
    }
}
