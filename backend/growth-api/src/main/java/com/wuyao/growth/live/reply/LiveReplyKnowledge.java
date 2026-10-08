package com.wuyao.growth.live.reply;

import com.wuyao.growth.common.web.BizException;
import com.wuyao.growth.knowledge.KnowledgeService;
import com.wuyao.growth.live.*;
import com.wuyao.growth.live.script.ScriptPrompt;
import com.wuyao.growth.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a reply to a viewer may draw on. Once a session has started this is the knowledge snapshot
 * taken at that moment; a draft reads the libraries as they are now. Call inside a transaction.
 */
@Component
@RequiredArgsConstructor
public class LiveReplyKnowledge {
    private final LiveSessionProductRepository selected;
    private final LiveSessionQaRepository qas;
    private final LiveKnowledgeSnapshotRepository snapshots;
    private final KnowledgeService knowledge;
    private final ProductService products;

    public record Candidate(String question, String answer, String source) { }

    /** Saved questions and answers in priority order: session, then product, then store. */
    public List<Candidate> candidates(LiveSession session, Long userId) {
        if (!"DRAFT".equals(session.getStatus())) {
            return snapshots.findBySessionIdOrderByPriorityAscIdAsc(session.getId()).stream()
                    .map(row -> new Candidate(row.getQuestion(), row.getAnswer(), row.getSourceType())).toList();
        }
        return library(session, userId);
    }

    /** The same, read from the libraries as they are now, whatever the session's state. */
    public List<Candidate> library(LiveSession session, Long userId) {
        List<Long> ids = productIds(session);
        List<Candidate> result = new ArrayList<>();
        for (var qa : qas.findBySessionIdOrderByIdAsc(session.getId())) {
            if (!"STORE_KNOWLEDGE".equals(qa.getPersistMode()) && qa.getTargetId() != null && !ids.contains(qa.getTargetId())) continue;
            result.add(new Candidate(qa.getQuestion(), qa.getAnswer(), "SESSION"));
        }
        for (Long productId : ids) {
            for (var faq : products.listFaqs(productId, userId)) {
                if ("ACTIVE".equals(faq.status())) result.add(new Candidate(faq.question(), faq.answer(), "PRODUCT_FAQ"));
            }
        }
        var entries = knowledge.context(session.getStoreId(), ids, userId).entries();
        entries.stream().filter(entry -> "PRODUCT".equals(entry.scope())).forEach(entry ->
                result.add(new Candidate(entry.question(), entry.answer(), "PRODUCT")));
        entries.stream().filter(entry -> "STORE".equals(entry.scope())).forEach(entry ->
                result.add(new Candidate(entry.question(), entry.answer(), "STORE")));
        return result;
    }

    /** The saved facts of every product in the session, in the wording the narration prompt uses. */
    public String productFacts(LiveSession session, Long userId) {
        StringBuilder facts = new StringBuilder();
        for (Long productId : productIds(session)) {
            try {
                String product = ScriptPrompt.facts(products.get(productId, userId));
                if (!facts.isEmpty()) facts.append("\n\n");
                facts.append(product);
            } catch (BizException e) {
                // A product removed from the library after the session was set up has nothing to say.
            }
        }
        return facts.toString();
    }

    /** The saved question that is this comment word for word, ignoring case, spacing and punctuation. */
    public static Candidate exact(List<Candidate> candidates, String comment) {
        String wanted = normalize(comment);
        return wanted.isEmpty() ? null : candidates.stream()
                .filter(candidate -> normalize(candidate.question()).equals(wanted)).findFirst().orElse(null);
    }

    public static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]", "");
    }

    private List<Long> productIds(LiveSession session) {
        return selected.findBySessionIdOrderBySortOrderAscIdAsc(session.getId()).stream()
                .map(LiveSessionProduct::getProductId).toList();
    }
}
