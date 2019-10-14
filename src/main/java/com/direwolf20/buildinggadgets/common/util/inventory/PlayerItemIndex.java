package com.direwolf20.buildinggadgets.common.util.inventory;

import com.direwolf20.buildinggadgets.api.materials.MaterialList;
import com.direwolf20.buildinggadgets.api.materials.inventory.IObjectHandle;
import com.direwolf20.buildinggadgets.api.materials.inventory.IUniqueObject;
import com.direwolf20.buildinggadgets.api.materials.inventory.UniqueItem;
import com.google.common.collect.*;
import com.google.common.collect.Multiset.Entry;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.*;

public final class PlayerItemIndex implements IItemIndex {
    private Map<Class<?>, Map<Object, List<IObjectHandle<?>>>> handleMap;
    private List<IInsertProvider> insertProviders;
    private final ItemStack stack;
    private final PlayerEntity player;

    public PlayerItemIndex(ItemStack stack, PlayerEntity player) {
        this.stack = stack;
        this.player = player;
        reIndex();
    }

    @Override
    public Multiset<IUniqueObject<?>> insert(Multiset<IUniqueObject<?>> items, boolean simulate) {
        Multiset<IUniqueObject<?>> copy = HashMultiset.create(items);
        Multiset<IUniqueObject<?>> toRemove = HashMultiset.create();
        for (Multiset.Entry<IUniqueObject<?>> entry : copy.entrySet()) {
            int remainingCount = insertObject(entry.getElement(), entry.getCount(), simulate);
            if (remainingCount < entry.getCount())
                toRemove.add(entry.getElement(), entry.getCount() - remainingCount);
        }
        Multisets.removeOccurrences(copy, toRemove);

        return copy;
    }

    private int insertObject(IUniqueObject<?> obj, int count, boolean simulate) {
        return obj.trySimpleInsert(count)
                .map(itemStack -> performSimpleInsert(itemStack, count, simulate))
                .orElseGet(() -> performComplexInsert(obj, count, simulate));
    }

    private int performSimpleInsert(ItemStack stack, int count, boolean simulate) {
        int remainingCount = count;
        for (IInsertProvider insertProvider : insertProviders) {
            remainingCount = insertProvider.insert(stack, remainingCount, simulate);
        }
        return remainingCount;
    }

    private int performComplexInsert(IUniqueObject<?> obj, int count, boolean simulate) {
        int remainingCount = count;
        List<IObjectHandle<?>> handles = handleMap
                .getOrDefault(obj.getIndexClass(), ImmutableMap.of())
                .getOrDefault(obj.getIndexObject(), ImmutableList.of());
        for (Iterator<IObjectHandle<?>> it = handles.iterator(); it.hasNext() && remainingCount >= 0; ) {
            IObjectHandle<?> handle = it.next();
            int match = handle.insert(obj, remainingCount, simulate);
            if (match > 0)
                remainingCount -= match;
            if (handle.shouldCleanup())
                it.remove();
        }
        remainingCount = Math.max(0, remainingCount);
        if (remainingCount > 0) {
            final int remainder = remainingCount;//pass it to lambda
            return obj.tryCreateComplexInsertStack(Collections.unmodifiableMap(handleMap), count)
                    .map(stack -> {
                        List<IObjectHandle<?>> emptyHandles = handleMap
                                .computeIfAbsent(Item.class, c -> new HashMap<>())
                                .getOrDefault(Items.AIR, ImmutableList.of());
                        int innerRemainder = remainder;
                        for (Iterator<IObjectHandle<?>> it = emptyHandles.iterator(); it.hasNext() && innerRemainder >= 0; ) {
                            IObjectHandle<?> handle = it.next();
                            UniqueItem item = UniqueItem.ofStack(stack);
                            int match = handle.insert(item, innerRemainder, simulate);
                            if (match > 0)
                                innerRemainder -= match;
                            it.remove();
                            handleMap.get(Item.class)
                                    .computeIfAbsent(item.getIndexObject(), i -> new ArrayList<>())
                                    .add(handle);
                        }
                        while (innerRemainder > 0) {
                            ItemStack copy = stack.copy();
                            copy.setCount(Math.min(innerRemainder, copy.getMaxStackSize()));
                            innerRemainder -= copy.getCount();
                            ItemEntity itemEntity = new ItemEntity(player.world, player.posX, player.posY, player.posZ, copy);
                            player.world.addEntity(itemEntity);
                        }
                        return innerRemainder;
                    })
                    .orElse(remainder);
        }
        return 0;
    }

    @Override
    public void reIndex() {
        this.handleMap = InventoryHelper.indexMap(stack, player);
        this.insertProviders = InventoryHelper.indexInsertProviders(stack, player);
    }

    @Override
    public MatchResult tryMatch(MaterialList list) {
        MatchResult result = null;
        for (ImmutableMultiset<IUniqueObject<?>> multiset : list) {
            result = match(list, multiset, true);
            if (result.isSuccess())
                return MatchResult.success(list, result.getFoundItems(), multiset);
        }
        return result == null ? MatchResult.success(list, ImmutableMultiset.of(), ImmutableMultiset.of()) : evaluateFailingOptionFoundItems(list);
    }

    private MatchResult evaluateFailingOptionFoundItems(MaterialList list) {
        Multiset<IUniqueObject<?>> multiset = HashMultiset.create();
        for (ImmutableMultiset<IUniqueObject<?>> option : list.getItemOptions()) {
            for (Entry<IUniqueObject<?>> entry : option.entrySet()) {
                multiset.setCount(entry.getElement(), Math.max(multiset.count(entry.getElement()), entry.getCount()));
            }
        }
        multiset.addAll(list.getRequiredItems());
        MatchResult result = match(list, multiset, true);
        if (result.isSuccess())
            throw new RuntimeException("This should not be possible! The the content changed between matches?!?");
        Iterator<ImmutableMultiset<IUniqueObject<?>>> it = list.iterator();
        return it.hasNext() ? MatchResult.failure(list, result.getFoundItems(), it.next()) : result;
    }

    private MatchResult match(MaterialList list, Multiset<IUniqueObject<?>> multiset, boolean simulate) {
        ImmutableMultiset.Builder<IUniqueObject<?>> availableBuilder = ImmutableMultiset.builder();
        boolean failure = false;
        for (Entry<IUniqueObject<?>> entry : multiset.entrySet()) {
            int remainingCount = entry.getCount();
            Class<?> indexClass = entry.getElement().getIndexClass();
            List<IObjectHandle<?>> entries = handleMap
                    .getOrDefault(indexClass, ImmutableMap.of())
                    .getOrDefault(entry.getElement().getIndexObject(), ImmutableList.of());
            for (Iterator<IObjectHandle<?>> it = entries.iterator(); it.hasNext() && remainingCount >= 0; ) {
                IObjectHandle<?> handle = it.next();
                int match = handle.match(entry.getElement(), remainingCount, simulate);
                if (match > 0)
                    remainingCount -= match;
                if (handle.shouldCleanup()) {
                    it.remove();
                    if (indexClass == Item.class)  //make it ready for insertion if this is an Item handle
                        handleMap.computeIfAbsent(Item.class, c -> new HashMap<>())
                                .computeIfAbsent(Items.AIR, i -> new ArrayList<>())
                                .add(handle);
                }
            }
            remainingCount = Math.max(0, remainingCount);
            if (remainingCount > 0)
                failure = true;
            availableBuilder.addCopies(entry.getElement(), entry.getCount() - remainingCount);
        }
        if (failure)
            return MatchResult.failure(list, availableBuilder.build(), ImmutableMultiset.of());
        return MatchResult.success(list, availableBuilder.build(), ImmutableMultiset.of());
    }

    @Override
    public boolean applyMatch(MatchResult result) {
        if (! result.isSuccess())
            return false;
        return match(result.getMatchedList(), result.getChosenOption(), false).isSuccess();
    }

}