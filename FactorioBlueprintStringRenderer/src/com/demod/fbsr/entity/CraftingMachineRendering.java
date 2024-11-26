package com.demod.fbsr.entity;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.luaj.vm2.LuaValue;

import com.demod.factorio.DataTable;
import com.demod.factorio.FactorioData;
import com.demod.factorio.Utils;
import com.demod.factorio.prototype.DataPrototype;
import com.demod.factorio.prototype.EntityPrototype;
import com.demod.factorio.prototype.RecipePrototype;
import com.demod.fbsr.Direction;
import com.demod.fbsr.Layer;
import com.demod.fbsr.RenderUtils;
import com.demod.fbsr.Renderer;
import com.demod.fbsr.Sprite;
import com.demod.fbsr.WorldMap;
import com.demod.fbsr.bs.BSEntity;
import com.demod.fbsr.fp.FPWorkingVisualisations;

public abstract class CraftingMachineRendering extends SimpleEntityRendering {

	private FPWorkingVisualisations protoGraphicsSet;
	private boolean protoOffWhenNoFluidRecipe;
	private List<Point2D.Double> protoFluidBoxes;

	@Override
	public void createRenderers(Consumer<Renderer> register, WorldMap map, DataTable dataTable, BSEntity entity) {
		super.createRenderers(register, map, dataTable, entity);

		register.accept(RenderUtils.spriteRenderer(protoGraphicsSet.createSprites(entity.direction, 0), entity,
				protoSelectionBox));

		Sprite spriteIcon = new Sprite();

		Optional<String> recipe = entity.recipe;
		if (recipe.isPresent()) {
			Optional<RecipePrototype> optRecipe = dataTable.getRecipe(recipe.get());
			if (optRecipe.isPresent()) {
				RecipePrototype protoRecipe = optRecipe.get();
				if (!protoRecipe.lua().get("icon").isnil() || !protoRecipe.lua().get("icons").isnil()) {
					spriteIcon.image = FactorioData.getIcon(protoRecipe);
				} else {
					String name;
					if (protoRecipe.lua().get("results") != LuaValue.NIL) {
						name = protoRecipe.lua().get("results").get(1).get("name").toString();
					} else {
						name = protoRecipe.lua().get("result").toString();
					}
					Optional<? extends DataPrototype> protoProduct = dataTable.getItem(name);
					if (!protoProduct.isPresent()) {
						protoProduct = dataTable.getFluid(name);
					}
					spriteIcon.image = protoProduct.map(FactorioData::getIcon).orElse(RenderUtils.EMPTY_IMAGE);
				}

				spriteIcon.source = new Rectangle(0, 0, spriteIcon.image.getWidth(), spriteIcon.image.getHeight());
				spriteIcon.bounds = new Rectangle2D.Double(-0.7, -1.0, 1.4, 1.4);

				Renderer delegate = RenderUtils.spriteRenderer(spriteIcon, entity, protoSelectionBox);
				register.accept(new Renderer(Layer.ENTITY_INFO_ICON, delegate.getBounds()) {
					@Override
					public void render(Graphics2D g) throws Exception {
						g.setColor(new Color(0, 0, 0, 180));
						g.fill(spriteIcon.bounds);
						delegate.render(g);
					}
				});
			}
		}
	}

	@Override
	public void defineEntity(Bindings bind, LuaValue lua) {
	}

	@Override
	public void initFromPrototype(DataTable dataTable, EntityPrototype prototype) {
		super.initFromPrototype(dataTable, prototype);

		protoGraphicsSet = new FPWorkingVisualisations(prototype.lua().get("graphics_set"));

		LuaValue fluidBoxesLua = prototype.lua().get("fluid_boxes");

		if (!fluidBoxesLua.isnil()) {
			protoFluidBoxes = new ArrayList<>();
			protoOffWhenNoFluidRecipe = fluidBoxesLua.get("off_when_no_fluid_recipe").optboolean(false);
			Utils.forEach(fluidBoxesLua, fluidBoxLua -> {
				if (!fluidBoxLua.istable()) {
					return;
				}
				Utils.forEach(fluidBoxLua.get("pipe_connections"), pipeConnectionLua -> {
					Point2D.Double offset = Utils.parsePoint2D(pipeConnectionLua.get("position"));
					if (Math.abs(offset.y) > Math.abs(offset.x)) {
						offset.y += -Math.signum(offset.y);
					} else {
						offset.x += -Math.signum(offset.x);
					}
					protoFluidBoxes.add(offset);
				});
			});
		} else {
			protoFluidBoxes = null;
			protoOffWhenNoFluidRecipe = true;
		}
	}

	@Override
	public void populateLogistics(WorldMap map, DataTable dataTable, BSEntity entity) {
		Optional<String> recipe = entity.recipe;
		if (recipe.isPresent()) {
			Optional<RecipePrototype> optRecipe = dataTable.getRecipe(recipe.get());
			if (optRecipe.isPresent()) {
				RecipePrototype protoRecipe = optRecipe.get();
				setLogisticMachine(map, dataTable, entity, protoRecipe);
			}
		}
	}

	@Override
	public void populateWorldMap(WorldMap map, DataTable dataTable, BSEntity entity) {
		Optional<String> recipe = entity.recipe;
		boolean hasFluid = false;
		if (recipe.isPresent()) {
			Optional<RecipePrototype> optRecipe = dataTable.getRecipe(recipe.get());
			if (optRecipe.isPresent()) {
				RecipePrototype protoRecipe = optRecipe.get();

				List<LuaValue> items = new ArrayList<>();
				Utils.forEach(protoRecipe.lua().get("ingredients"), (Consumer<LuaValue>) items::add);
				LuaValue resultsLua = protoRecipe.lua().get("results");
				if (resultsLua != LuaValue.NIL) {
					items.add(resultsLua);
				}
				hasFluid = items.stream().anyMatch(lua -> {
					LuaValue typeLua = lua.get("type");
					return typeLua != LuaValue.NIL && typeLua.toString().equals("fluid");
				});
			}
		}

		if ((protoFluidBoxes != null) && (!protoOffWhenNoFluidRecipe || hasFluid)) {
			for (Point2D.Double offset : protoFluidBoxes) {
				Point2D.Double pos = entity.direction.left()
						.offset(entity.direction.back().offset(entity.position.createPoint(), offset.y), offset.x);
				Direction direction = offset.y > 0 ? entity.direction.back() : entity.direction;
				map.setPipe(pos, direction);
			}
		}
	}

}
