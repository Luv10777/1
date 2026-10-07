package com.wuyao.growth.voice;

import com.wuyao.growth.store.StoreClosureGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * A voice has to stay open to at least one store that is in business, or nobody could reach it
 * again. So a store holding the only opening of a voice is not closed until the owner moves or
 * deletes that voice; once a store is closed, its openings go with it.
 */
@Component
@RequiredArgsConstructor
public class VoiceStoreClosureGuard implements StoreClosureGuard {
    private final VoiceSampleRepository samples;
    private final VoiceSampleStoreRepository grants;

    @Override
    public Optional<String> blocksClosing(Long storeId) {
        long stranded = samples.countOpenOnlyTo(storeId);
        return stranded == 0 ? Optional.empty() : Optional.of("有 " + stranded
                + " 个声音样本只开放给这家店，关店后就无法再使用。请先把它们开放给其他门店，或删除后再关店");
    }

    @Override
    public void closed(Long storeId) {
        grants.deleteAll(grants.findByStoreId(storeId));
    }
}
