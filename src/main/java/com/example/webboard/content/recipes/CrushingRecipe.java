package com.example.webboard.content.recipes;

import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

/**
 * Worked-example recipe: 1 input → 1 output, no fluid, no heat.
 *
 * For simplicity and to avoid Create's 6.0.6 ProcessingRecipe refactor's deep API surface
 * (Factory + ProcessingRecipeSerializer + ProcessingRecipeParams codec wiring), this recipe
 * uses the vanilla {@link Recipe} interface directly. See ModRecipes for the trade-off note
 * and the migration path to ProcessingRecipe for real addons.
 *
 * 1.21.1 API note: Recipe<T extends RecipeInput> (NOT Recipe<Container> — that was 1.20-).
 * getResultItem(Provider) — RegistryAccess was renamed to Provider in 1.21.
 * getId() is on ItemStack / sub-recipe, not on the base Recipe interface here — see getSerializer()/getType() below.
 */
public class CrushingRecipe implements Recipe<RecipeInput> {

    private final ResourceLocation id;
    private final Ingredient input;
    private final ItemStack output;

    public CrushingRecipe(ResourceLocation id, Ingredient input, ItemStack output) {
        this.id = id;
        this.input = input;
        this.output = output;
    }

    @Override public boolean matches(RecipeInput input, Level level) {
        return this.input.test(input.getItem(0));
    }

    @Override public ItemStack assemble(RecipeInput input, Provider provider) {
        return output.copy();
    }

    @Override public boolean canCraftInDimensions(int w, int h) { return true; }

    @Override public ItemStack getResultItem(Provider provider) { return output; }

    @Override public NonNullList<Ingredient> getIngredients() {
        return NonNullList.of(Ingredient.EMPTY, input);
    }

    /** ResourceLocation id is set in ctor; exposed for JSON lookup. */
    public ResourceLocation getRecipeId() { return id; }

    @Override public RecipeSerializer<?> getSerializer() {
        return com.example.webboard.registry.ModRecipes.CRUSHING_SERIALIZER.get();
    }

    @Override public RecipeType<?> getType() {
        return com.example.webboard.registry.ModRecipes.CRUSHING_TYPE.get();
    }
}