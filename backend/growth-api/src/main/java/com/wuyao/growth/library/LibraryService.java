package com.wuyao.growth.library;

import com.wuyao.growth.common.tenant.TenantContext;
import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.common.web.ErrorCode;
import com.wuyao.growth.common.web.PageResult;
import com.wuyao.growth.creative.image.ImageCreationService;
import com.wuyao.growth.creative.image.ImageDtos;
import com.wuyao.growth.video.VideoDtos;
import com.wuyao.growth.video.VideoWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 作品库：生成完的图片和视频由用户手动保存进来，可放进文件夹（只有一层）。
 * 每个账号只管自己的作品库；作品本身仍归图片 / 视频模块，这里只记位置。
 * 移出作品库、删除文件夹都只删位置记录，生成记录和文件不动，之后还能从创作记录里重新保存。
 */
@Service
@RequiredArgsConstructor
public class LibraryService {
    static final String IMAGE = "IMAGE";
    static final String VIDEO = "VIDEO";

    private final WorkFolderRepository folders;
    private final LibraryWorkRepository works;
    private final ImageCreationService images;
    private final VideoWorkflowService videos;

    @Transactional(readOnly = true)
    public LibraryDtos.Folders folders(Long userId) {
        Map<Long, Long> counts = counts(userId);
        var list = folders.findByOwnerIdOrderByCreatedAtAscIdAsc(userId).stream()
                .map(f -> view(f, counts.getOrDefault(f.getId(), 0L))).toList();
        return new LibraryDtos.Folders(list, counts.getOrDefault(null, 0L));
    }

    @Transactional
    public LibraryDtos.Folder createFolder(String name, Long userId) {
        Long tenantId = lock(userId);
        String value = folderName(name);
        if (folders.existsByOwnerIdAndName(userId, value)) throw BizException.of(ErrorCode.CONFLICT, "已有同名文件夹");
        var folder = new WorkFolder();
        folder.setTenantId(tenantId);
        folder.setOwnerId(userId);
        folder.setName(value);
        return view(folders.saveAndFlush(folder), 0);
    }

    @Transactional
    public LibraryDtos.Folder renameFolder(Long id, String name, Long userId) {
        lock(userId);
        var folder = folder(id, userId);
        String value = folderName(name);
        if (folders.existsByOwnerIdAndNameAndIdNot(userId, value, id)) throw BizException.of(ErrorCode.CONFLICT, "已有同名文件夹");
        folder.setName(value);
        folder.setUpdatedAt(Instant.now());
        return view(folders.saveAndFlush(folder), counts(userId).getOrDefault(id, 0L));
    }

    /** 删除文件夹时，里面的作品一起移出作品库。 */
    @Transactional
    public void deleteFolder(Long id, Long userId) {
        lock(userId);
        var folder = folder(id, userId);
        works.deleteByFolderId(folder.getId());
        folders.delete(folder);
    }

    /** folder：all 全部作品，root 只看根目录，或文件夹 ID。kind：IMAGE / VIDEO，为空表示都要。 */
    @Transactional(readOnly = true)
    public PageResult<LibraryDtos.Work> works(String folder, String kind, int page, int size, Long userId) {
        if (page < 0 || size < 1 || size > 50) throw BizException.of(ErrorCode.BAD_REQUEST, "分页参数无效");
        if (kind != null && !Set.of(IMAGE, VIDEO).contains(kind)) throw BizException.of(ErrorCode.BAD_REQUEST, "作品类型不合法");
        boolean all = folder == null || folder.isBlank() || "all".equals(folder);
        Long folderId = null;
        if (!all && !"root".equals(folder)) {
            try {
                folderId = Long.valueOf(folder);
            } catch (NumberFormatException e) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "文件夹参数无效");
            }
            folder(folderId, userId);
        }
        var rows = works.page(userId, folderId, all, kind, PageRequest.of(page, size));
        var imageIds = rows.stream().filter(w -> IMAGE.equals(w.getKind())).map(LibraryWork::getSourceId).toList();
        var videoIds = rows.stream().filter(w -> VIDEO.equals(w.getKind())).map(LibraryWork::getSourceId).toList();
        Map<Long, ImageDtos.Work> imageWorks = imageIds.isEmpty() ? Map.of() : images.worksByItemIds(imageIds);
        Map<Long, VideoDtos.History> videoWorks = videoIds.isEmpty() ? Map.of() : videos.worksByIds(videoIds);
        // 源记录已经不可用（例如成片被清理）的不显示。
        var items = rows.stream().map(w -> new LibraryDtos.Work(w.getId(), w.getKind(), w.getSourceId(), w.getFolderId(),
                        w.getSavedAt(),
                        IMAGE.equals(w.getKind()) ? imageWorks.get(w.getSourceId()) : null,
                        VIDEO.equals(w.getKind()) ? videoWorks.get(w.getSourceId()) : null))
                .filter(w -> w.image() != null || w.video() != null).toList();
        return PageResult.of(rows, items);
    }

    /** 保存到作品库。已经保存过的作品再保存一次，等于移到新位置。 */
    @Transactional
    public LibraryDtos.Saved save(LibraryDtos.SaveRequest req, Long userId) {
        Long tenantId = lock(userId);
        String folderName = req.folderId() == null ? null : folder(req.folderId(), userId).getName();
        if (IMAGE.equals(req.kind())) images.requireSavableWork(req.sourceId(), userId);
        else videos.requireSavableWork(req.sourceId(), userId);
        var work = works.findByKindAndSourceId(req.kind(), req.sourceId()).orElseGet(() -> {
            var created = new LibraryWork();
            created.setTenantId(tenantId);
            created.setOwnerId(userId);
            created.setKind(req.kind());
            created.setSourceId(req.sourceId());
            return created;
        });
        if (!Objects.equals(work.getOwnerId(), userId)) throw BizException.of(ErrorCode.NOT_FOUND, "作品不存在");
        work.setFolderId(req.folderId());
        work = works.saveAndFlush(work);
        return new LibraryDtos.Saved(work.getSourceId(), work.getId(), work.getFolderId(), folderName);
    }

    @Transactional
    public void move(Long workId, Long folderId, Long userId) {
        lock(userId);
        var work = work(workId, userId);
        if (folderId != null) folder(folderId, userId);
        work.setFolderId(folderId);
    }

    /** 批量移动。只要有一件不在这个账号的作品库里，整批都不动。返回移动的件数。 */
    @Transactional
    public int moveMany(List<Long> workIds, Long folderId, Long userId) {
        lock(userId);
        var ids = Set.copyOf(workIds);
        if (folderId != null) folder(folderId, userId);
        var rows = works.findByOwnerIdAndIdIn(userId, ids);
        if (rows.size() != ids.size()) throw BizException.of(ErrorCode.NOT_FOUND, "有作品已不在作品库里，请刷新后重试");
        rows.forEach(w -> w.setFolderId(folderId));
        return rows.size();
    }

    @Transactional
    public void remove(Long workId, Long userId) {
        lock(userId);
        works.delete(work(workId, userId));
    }

    /** 生成页用：这些作品里哪些已经保存、保存在哪。没保存的不返回。 */
    @Transactional(readOnly = true)
    public List<LibraryDtos.Saved> saved(String kind, List<Long> sourceIds, Long userId) {
        if (!Set.of(IMAGE, VIDEO).contains(kind)) throw BizException.of(ErrorCode.BAD_REQUEST, "作品类型不合法");
        if (sourceIds.size() > 100) throw BizException.of(ErrorCode.BAD_REQUEST, "一次最多查询 100 件作品");
        if (sourceIds.isEmpty()) return List.of();
        Map<Long, String> names = new HashMap<>();
        folders.findByOwnerIdOrderByCreatedAtAscIdAsc(userId).forEach(f -> names.put(f.getId(), f.getName()));
        return works.findByOwnerIdAndKindAndSourceIdIn(userId, kind, sourceIds).stream()
                .map(w -> new LibraryDtos.Saved(w.getSourceId(), w.getId(), w.getFolderId(),
                        w.getFolderId() == null ? null : names.get(w.getFolderId())))
                .toList();
    }

    private Long lock(Long userId) {
        Long tenantId = TenantContext.require();
        folders.lockOwner(tenantId, userId);
        return tenantId;
    }

    private Map<Long, Long> counts(Long userId) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : works.countByFolder(userId)) counts.put((Long) row[0], (Long) row[1]);
        return counts;
    }

    private WorkFolder folder(Long id, Long userId) {
        return folders.findByIdAndOwnerId(id, userId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "文件夹不存在"));
    }

    private LibraryWork work(Long id, Long userId) {
        return works.findByIdAndOwnerId(id, userId).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND, "作品不在作品库里"));
    }

    private static String folderName(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty()) throw BizException.of(ErrorCode.BAD_REQUEST, "请填写文件夹名称");
        if (value.codePointCount(0, value.length()) > 30) throw BizException.of(ErrorCode.BAD_REQUEST, "文件夹名称最多 30 个字");
        return value;
    }

    private static LibraryDtos.Folder view(WorkFolder f, long count) {
        return new LibraryDtos.Folder(f.getId(), f.getName(), count, f.getCreatedAt());
    }
}
