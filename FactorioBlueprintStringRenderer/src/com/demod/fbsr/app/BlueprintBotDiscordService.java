package com.demod.fbsr.app;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.commons.text.similarity.LevenshteinDistance;
import org.json.JSONException;
import org.json.JSONObject;

import com.demod.dcba.AutoCompleteHandler;
import com.demod.dcba.CommandReporting;
import com.demod.dcba.DCBA;
import com.demod.dcba.DiscordBot;
import com.demod.dcba.EventReply;
import com.demod.dcba.SlashCommandEvent;
import com.demod.dcba.SlashCommandHandler;
import com.demod.factorio.Config;
import com.demod.factorio.Utils;
import com.demod.factorio.fakelua.LuaValue;
import com.demod.factorio.prototype.DataPrototype;
import com.demod.fbsr.BlueprintFinder;
import com.demod.fbsr.BlueprintFinder.FindBlueprintResult;
import com.demod.fbsr.FBSR;
import com.demod.fbsr.FactorioManager;
import com.demod.fbsr.RenderRequest;
import com.demod.fbsr.RenderResult;
import com.demod.fbsr.RenderUtils;
import com.demod.fbsr.WebUtils;
import com.demod.fbsr.app.WatchdogService.WatchdogReporter;
import com.demod.fbsr.bs.BSBlueprint;
import com.demod.fbsr.bs.BSBlueprintBook;
import com.demod.fbsr.bs.BSBlueprintString;
import com.demod.fbsr.bs.BSDeconstructionPlanner;
import com.demod.fbsr.bs.BSUpgradePlanner;
import com.demod.fbsr.gui.layout.GUILayoutBlueprint;
import com.demod.fbsr.gui.layout.GUILayoutBook;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AbstractIdleService;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.MessageEmbed.Field;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.context.ContextInteraction.ContextTarget;
import net.dv8tion.jda.api.interactions.components.ItemComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.FileUpload;

public class BlueprintBotDiscordService extends AbstractIdleService {

	private static class CachedMessageImageResult {
		public final Optional<String> label;
		public final String messageId;

		public CachedMessageImageResult(Optional<String> label, String messageId) {
			this.label = label;
			this.messageId = messageId;
		}

	}

	public static class ImageShrinkResult {
		public final boolean scaled;
		public final double scale;
		public final byte[] data;
		public final String extension;

		public ImageShrinkResult(boolean scaled, double scale, byte[] data, String extension) {
			this.scaled = scaled;
			this.scale = scale;
			this.data = data;
			this.extension = extension;
		}
	}

	public static final Emoji EMOJI_BLUEPRINT = Emoji.fromFormatted("<:blueprint:1316556635761672244>");
	public static final Emoji EMOJI_BLUEPRINTBOOK = Emoji.fromFormatted("<:blueprintbook:1316556633073258569>");
	public static final Emoji EMOJI_DECONSTRUCTIONPLANNER = Emoji
			.fromFormatted("<:deconstructionplanner:1316556636621508688>");

	public static final Emoji EMOJI_UPGRADEPLANNER = Emoji.fromFormatted("<:upgradeplanner:1316556634528546887>");

	public static final Emoji EMOJI_SEARCH = Emoji.fromFormatted("<:search:1319740035825799259>");

	private static Cache<String, CachedMessageImageResult> recentLazyLoadedMessages = CacheBuilder.newBuilder()//
			.maximumSize(1000).build();

	public static final int MAX_FILE_SIZE = 10 << 20; // JDA has not updated 25MB -> 10MB yet

	private static OptionalDouble optDouble(Optional<Double> value) {
		return value.map(OptionalDouble::of).orElse(OptionalDouble.empty());
	}

	private static OptionalInt optInt(Optional<Long> value) {
		return value.map(Long::intValue).map(OptionalInt::of).orElse(OptionalInt.empty());
	}

	private static ImageShrinkResult shrinkImageToFitUploadLimit(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();

		boolean scaled = false;
		double scale = 1.0;

		try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			while (true) {

				BufferedImage scaledImage;
				if (scaled) {
					int scaledWidth = (int) (width * scale);
					int scaledHeight = (int) (height * scale);
					scaledImage = new BufferedImage(scaledWidth, scaledHeight, image.getType());
					Graphics2D g = scaledImage.createGraphics();
					g.drawImage(image.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_FAST), 0, 0, null);
					g.dispose();
				} else {
					scaledImage = image;
				}

				if (!scaled) {
					ImageIO.write(scaledImage, "PNG", baos);
					if (baos.size() <= MAX_FILE_SIZE) {
						return new ImageShrinkResult(scaled, scale, baos.toByteArray(), "png");
					}
					baos.reset();
				}

				if (!ImageIO.write(scaledImage, "JPG", baos)) {
					BufferedImage noAlphaImage = new BufferedImage(scaledImage.getWidth(), scaledImage.getHeight(),
							BufferedImage.TYPE_INT_RGB);
					Graphics2D g = noAlphaImage.createGraphics();
					g.drawImage(scaledImage, 0, 0, Color.black, null);
					g.dispose();
					ImageIO.write(noAlphaImage, "JPG", baos);
				}
				if (baos.size() <= MAX_FILE_SIZE) {
					return new ImageShrinkResult(scaled, scale, baos.toByteArray(), "jpg");
				}
				int size = baos.size();
				baos.reset();

				scaled = true;
				if (size > MAX_FILE_SIZE * 1.25) {
					// Approximation based on file size
					scale = MAX_FILE_SIZE / (double) size;
				} else {
					scale *= 0.9;
				}
			}

		} catch (IOException e) {
			throw new InternalError(e);
		}
	}

	private DiscordBot bot;

	private JSONObject configJson;

	private String hostingChannelID;

	// TODO
//	private AutoCompleteHandler createDataRawAutoCompleteHandler(Function<String[], Optional<LuaValue>> query) {
//		return (event) -> {
//			String name = event.getParamString("name").trim().toLowerCase();
//
//			if (name.isEmpty()) {
//				event.reply(ImmutableList.of());
//				return;
//			}
//
//			List<String> nameStartsWith = new ArrayList<>();
//			List<String> nameContains = new ArrayList<>();
//			map.keySet().stream().sorted().forEach(n -> {
//				String lowerCase = n.toLowerCase();
//				if (lowerCase.startsWith(name)) {
//					nameStartsWith.add(n);
//				} else if (lowerCase.contains(name)) {
//					nameContains.add(n);
//				}
//			});
//
//			List<String> choices = ImmutableList.<String>builder().addAll(nameStartsWith).addAll(nameContains).build()
//					.stream().limit(OptionData.MAX_CHOICES).collect(Collectors.toList());
//			event.reply(choices);
//		};
//	}

	private SlashCommandHandler createDataRawCommandHandler(Function<String[], Optional<LuaValue>> query) {
		return (event) -> {
			String key = event.getParamString("path");
			String[] path = key.split("\\.");
			Optional<LuaValue> lua = query.apply(path);
			if (!lua.isPresent()) {
				event.reply("I could not find a lua table for the path [`"
						+ Arrays.asList(path).stream().collect(Collectors.joining(", ")) + "`] :frowning:");
				return;
			}
			sendLuaDumpFile(event, "raw", key, lua.get());
		};
	}

	private AutoCompleteHandler createPrototypeAutoCompleteHandler(Map<String, ? extends DataPrototype> map) {
		return (event) -> {
			String name = event.getParamString("name").trim().toLowerCase();

			if (name.isEmpty()) {
				event.reply(ImmutableList.of());
				return;
			}

			List<String> nameStartsWith = new ArrayList<>();
			List<String> nameContains = new ArrayList<>();
			map.keySet().stream().sorted().forEach(n -> {
				String lowerCase = n.toLowerCase();
				if (lowerCase.startsWith(name)) {
					nameStartsWith.add(n);
				} else if (lowerCase.contains(name)) {
					nameContains.add(n);
				}
			});

			List<String> choices = ImmutableList.<String>builder().addAll(nameStartsWith).addAll(nameContains).build()
					.stream().limit(OptionData.MAX_CHOICES).collect(Collectors.toList());
			event.reply(choices);
		};
	}

	private SlashCommandHandler createPrototypeCommandHandler(String category,
			Map<String, ? extends DataPrototype> map) {
		return (event) -> {
			String search = event.getParamString("name");
			Optional<? extends DataPrototype> prototype = Optional.ofNullable(map.get(search));
			if (!prototype.isPresent()) {
				LevenshteinDistance levenshteinDistance = LevenshteinDistance.getDefaultInstance();
				List<String> suggestions = map.keySet().stream()
						.map(k -> new SimpleEntry<>(k, levenshteinDistance.apply(search, k)))
						.sorted((p1, p2) -> Integer.compare(p1.getValue(), p2.getValue())).limit(5).map(p -> p.getKey())
						.collect(Collectors.toList());
				event.reply("I could not find the " + category + " prototype for `" + search
						+ "`. :frowning:\nDid you mean:\n"
						+ suggestions.stream().map(s -> "\t - " + s).collect(Collectors.joining("\n")));
				return;
			}

			sendLuaDumpFile(event, category, prototype.get().getName(), prototype.get().lua().tovalue());
		};
	}

	public DiscordBot getBot() {
		return bot;
	}

	private void handleBlueprintCommand(SlashCommandEvent event)
			throws IOException, InterruptedException, ExecutionException {

		String content = event.getCommandString();

		Optional<Attachment> attachment = event.optParamAttachment("file");
		if (attachment.isPresent()) {
			content += " " + attachment.get().getUrl();
		}

		Optional<BSBlueprintString> optBlueprintString = BlueprintFinder.search(content, event.getReporting()).stream()
				.findFirst();

		if (optBlueprintString.isEmpty()) {
			event.replyEmbed(new EmbedBuilder()//
					.setColor(Color.red)//
					.setDescription("Blueprint string not found!")//
					.build());
			return;
		}

		BSBlueprintString blueprintString = optBlueprintString.get();
		CommandReporting reporting = event.getReporting();

		List<Long> renderTimes = new ArrayList<>();

		Optional<MessageEmbed> embed = Optional.empty();

		Optional<String> label = null;
		BufferedImage image = null;

		List<List<ItemComponent>> actionRows = new ArrayList<>();
		List<ItemComponent> actionButtonRow = new ArrayList<>();

		Future<Message> futBlueprintStringUpload = useDiscordForFileHosting(
				WebUtils.formatBlueprintFilename(blueprintString.findFirstLabel(), "txt"),
				blueprintString.getRaw().get());

		if (blueprintString.blueprint.isPresent()) {
			BSBlueprint blueprint = blueprintString.blueprint.get();

			if (event.optParamBoolean("simple").orElse(false)) {
				RenderRequest request = new RenderRequest(blueprint, reporting);
				RenderResult result = FBSR.renderBlueprint(request);
				image = result.image;
				renderTimes.add(result.renderTime);

			} else {
				GUILayoutBlueprint layout = new GUILayoutBlueprint();
				layout.setBlueprint(blueprint);
				layout.setReporting(reporting);
				image = layout.generateDiscordImage();
				renderTimes.add(layout.getResult().renderTime);

				if (layout.getResult().renderScale < 0.501) {
					actionButtonRow
							.add(Button.secondary("reply-zoom|" + futBlueprintStringUpload.get().getId(), "Zoom In")
									.withEmoji(EMOJI_SEARCH));
				}
			}

			label = blueprint.label;

		} else if (blueprintString.blueprintBook.isPresent()) {
			BSBlueprintBook book = blueprintString.blueprintBook.get();

			if (book.blueprints.isEmpty()) {
				event.replyEmbed(new EmbedBuilder()//
						.setColor(Color.red)//
						.setDescription("Blueprint Book is empty!")//
						.build());
				return;
			}

			GUILayoutBook layout = new GUILayoutBook();
			layout.setBook(book);
			layout.setReporting(reporting);
			image = layout.generateDiscordImage();
			renderTimes.add(layout.getResults().stream().mapToLong(r -> r.renderTime).sum());

			List<BSBlueprint> blueprints = book.getAllBlueprints();
			StringSelectMenu.Builder menuBuilder = StringSelectMenu.create("reply-book-blueprint");
			for (int i = 0; i < blueprints.size(); i++) {
				if (i < 25) {
					BSBlueprint blueprint = blueprints.get(i);
					menuBuilder.addOption(blueprint.label.orElse("Untitled Blueprint " + (i + 1)),
							futBlueprintStringUpload.get().getId() + "|" + i);
				}
				// TODO figure out how to handle more than 25 blueprint options
			}
			actionRows.add(ImmutableList.of(menuBuilder.build()));
			// TODO each blueprint renders and upload

			label = book.label;

		} else if (blueprintString.deconstructionPlanner.isPresent()) {
			EmbedBuilder builder = new EmbedBuilder();
			BSDeconstructionPlanner planner = blueprintString.deconstructionPlanner.get();
			builder.setColor(Color.darkGray);
			builder.setDescription("Blueprint string is a deconstruction planner.");
			if (planner.label.isPresent()) {
				builder.addField(new Field("Label", planner.label.get(), false));
			}
			if (planner.description.isPresent()) {
				builder.addField(new Field("Description", planner.description.get(), false));
			}
			embed = Optional.of(builder.build());
			// TODO more details from deconstruction planner

		} else if (blueprintString.upgradePlanner.isPresent()) {
			EmbedBuilder builder = new EmbedBuilder();
			BSUpgradePlanner planner = blueprintString.upgradePlanner.get();
			builder.setColor(Color.darkGray);
			builder.setDescription("Blueprint string is an upgrade planner.");
			if (planner.label.isPresent()) {
				builder.addField(new Field("Label", planner.label.get(), false));
			}
			if (planner.description.isPresent()) {
				builder.addField(new Field("Description", planner.description.get(), false));
			}
			embed = Optional.of(builder.build());
			// TODO more details from upgrade planner
		}

		Emoji emoji = EMOJI_BLUEPRINT;
		if (blueprintString.blueprintBook.isPresent()) {
			emoji = EMOJI_BLUEPRINTBOOK;
		} else if (blueprintString.deconstructionPlanner.isPresent()) {
			emoji = EMOJI_DECONSTRUCTIONPLANNER;
		} else if (blueprintString.upgradePlanner.isPresent()) {
			emoji = EMOJI_UPGRADEPLANNER;
		}

		String actionId = "reply-blueprint|" + futBlueprintStringUpload.get().getId()
				+ blueprintString.findFirstLabel().map(s -> "|" + s).orElse("");
		if (actionId.length() > 100) {
			actionId = actionId.substring(0, 100);// XXX need a better solution
		}
		actionButtonRow.add(Button.secondary(actionId, "Download").withEmoji(emoji));

		if (!renderTimes.isEmpty()) {
			String renderTimesStr = renderTimes.stream().mapToLong(l1 -> l1).sum() + " ms" + (renderTimes.size() > 1
					? (" [" + renderTimes.stream().map(Object::toString).collect(Collectors.joining(", ")) + "]")
					: "");
			if (renderTimesStr.length() > 256) {
				renderTimesStr = renderTimesStr.substring(0, 256) + "...";
			}
			reporting.addField(new Field("Render Time", renderTimesStr, true));
		}

		if (!actionButtonRow.isEmpty()) {
			actionRows.add(actionButtonRow);
		}

		if (embed.isPresent()) {
			event.replyEmbed(embed.get(), actionRows);
		} else {

			ImageShrinkResult shrinkResult = shrinkImageToFitUploadLimit(image);
			String imageFilename = WebUtils.formatBlueprintFilename(label, shrinkResult.extension);
			event.replyFile(shrinkResult.data, imageFilename, actionRows);
		}
	}

	private void handleBlueprintCustomCommand(SlashCommandEvent event)
			throws IOException, InterruptedException, ExecutionException {

		String content = event.getCommandString();

		Optional<Attachment> attachment = event.optParamAttachment("file");
		if (attachment.isPresent()) {
			content += " " + attachment.get().getUrl();
		}

		List<BSBlueprintString> blueprintStrings = BlueprintFinder.search(content, event.getReporting());

		if (blueprintStrings.isEmpty()) {
			event.reply("No blueprint string was found. Make sure to specify a string, url, or file.");
			return;
		}

		if (blueprintStrings.size() > 1) {
			event.reply("More than one blueprint string was found. Please specify only one blueprint string.");
			return;
		}

		BSBlueprintString blueprintString = blueprintStrings.get(0);

		Future<Message> futBlueprintStringUpload = useDiscordForFileHosting(
				WebUtils.formatBlueprintFilename(blueprintString.findFirstLabel(), "txt"),
				blueprintString.getRaw().get());

		BSBlueprint blueprint = null;
		if (blueprintString.blueprint.isPresent()) {
			blueprint = blueprintString.blueprint.get();

		} else if (blueprintString.blueprintBook.isPresent()) {
			BSBlueprintBook book = blueprintString.blueprintBook.get();

			Optional<String> bookFilter = event.optParamString("book-filter");
			Optional<Long> bookIndex = event.optParamLong("book-index");

			List<BSBlueprint> blueprints = book.getAllBlueprints();
			if (bookFilter.isPresent()) {
				String match = bookFilter.get().toLowerCase();
				blueprints = blueprints.stream()
						.filter(b -> b.label.isPresent() && b.label.get().toLowerCase().contains(match))
						.collect(Collectors.toList());
			}

			if (bookIndex.isPresent()) {
				int index = bookIndex.get().intValue();

				if (index < 0 || index >= blueprints.size()) {
					event.reply("Book index out of range. There are " + blueprints.size() + " blueprints.");
					return;
				}

				blueprint = blueprints.get(index);

			} else {

				if (blueprints.isEmpty()) {
					event.reply("No blueprints in book matched the filter.");
					return;
				}

				if (blueprints.size() > 1) {
					event.reply(blueprints.size() + " blueprints in book matched the filter, please be more specific:\n"
							+ blueprints.stream().flatMap(b -> b.label.stream()).map(l -> "- " + l)
									.collect(Collectors.joining("\n")));
					return;
				}

				blueprint = blueprints.get(0);
			}
		}

		Optional<Long> minWidth = event.optParamLong("min-width");
		Optional<Long> minHeight = event.optParamLong("min-height");
		Optional<Long> maxWidth = event.optParamLong("max-width");
		Optional<Long> maxHeight = event.optParamLong("max-height");
		Optional<Double> maxScale = event.optParamDouble("max-scale");

		boolean showBackground = event.optParamBoolean("show-background").orElse(true);
		boolean showGridlines = event.optParamBoolean("show-gridlines").orElse(true);

		boolean showAltMode = event.optParamBoolean("show-alt-mode").orElse(true);
		boolean showPathOutputs = event.optParamBoolean("show-path-outputs").orElse(false);
		boolean showPathInputs = event.optParamBoolean("show-path-inputs").orElse(false);
		boolean showPathRails = event.optParamBoolean("show-path-rails").orElse(false);
		boolean showGridNumbers = event.optParamBoolean("show-grid-numbers").orElse(false);
		boolean showGridAboveBelts = event.optParamBoolean("show-grid-above-belts").orElse(false);

		boolean debugPathItems = event.optParamBoolean("debug-path-items").orElse(false);
		boolean debugPathRails = event.optParamBoolean("debug-path-rails").orElse(false);
		boolean debugEntityPlacement = event.optParamBoolean("debug-entity-placement").orElse(false);

		RenderRequest request = new RenderRequest(blueprint, event.getReporting());

		request.setMinWidth(optInt(minWidth));
		request.setMinHeight(optInt(minHeight));
		request.setMaxWidth(optInt(maxWidth));
		request.setMaxHeight(optInt(maxHeight));
		request.setMaxScale(optDouble(maxScale));

		request.setBackground(showBackground ? Optional.of(FBSR.GROUND_COLOR) : Optional.empty());
		request.setGridLines(showGridlines ? Optional.of(FBSR.GRID_COLOR) : Optional.empty());

		request.show.altMode = showAltMode;
		request.show.pathOutputs = showPathOutputs;
		request.show.pathInputs = showPathInputs;
		request.show.pathRails = showPathRails;
		request.show.gridNumbers = showGridNumbers;
		request.show.gridAboveBelts = showGridAboveBelts;

		request.debug.pathItems = debugPathItems;
		request.debug.pathRails = debugPathRails;
		request.debug.entityPlacement = debugEntityPlacement;

		RenderResult result = FBSR.renderBlueprint(request);

		event.getReporting().addField(new Field("Render Time", result.renderTime + " ms", true));

		Emoji emoji = EMOJI_BLUEPRINT;
		if (blueprintString.blueprintBook.isPresent()) {
			emoji = EMOJI_BLUEPRINTBOOK;
		} else if (blueprintString.deconstructionPlanner.isPresent()) {
			emoji = EMOJI_DECONSTRUCTIONPLANNER;
		} else if (blueprintString.upgradePlanner.isPresent()) {
			emoji = EMOJI_UPGRADEPLANNER;
		}
		String actionId = "reply-blueprint|" + futBlueprintStringUpload.get().getId()
				+ blueprintString.findFirstLabel().map(s -> "|" + s).orElse("");
		if (actionId.length() > 100) {
			actionId = actionId.substring(0, 100);// XXX need a better solution
		}
		Button btnDownload = Button.secondary(actionId, "Download").withEmoji(emoji);
		List<List<ItemComponent>> actionRows = ImmutableList.of(ImmutableList.of(btnDownload));

		ImageShrinkResult shrinkResult = shrinkImageToFitUploadLimit(result.image);
		String imageFilename = WebUtils.formatBlueprintFilename(blueprint.label, shrinkResult.extension);
		event.replyFile(shrinkResult.data, imageFilename, actionRows);
	}

	private void handleBlueprintItemsCommand(SlashCommandEvent event) throws IOException {
		String content = event.getCommandString();

		Optional<Attachment> attachment = event.optParamAttachment("file");
		if (attachment.isPresent()) {
			content += " " + attachment.get().getUrl();
		}

		List<BSBlueprintString> blueprintStrings = BlueprintFinder.search(content, event.getReporting());

		Map<String, Double> totalItems = new LinkedHashMap<>();
		for (BSBlueprintString bs : blueprintStrings) {
			for (BSBlueprint blueprint : bs.findAllBlueprints()) {
				Map<String, Double> items = FBSR.generateTotalItems(blueprint);
				items.forEach((k, v) -> {
					totalItems.compute(k, ($, old) -> old == null ? v : old + v);
				});
			}
		}

		if (!totalItems.isEmpty()) {
			// TODO figure out wiki naming to include modded
//			DataTable table = FactorioData.getTable();
			String responseContent = totalItems.entrySet().stream()
					.sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
//					.map(e -> table.getWikiItemName(e.getKey()) + ": " + RenderUtils.fmtDouble2(e.getValue()))
					.map(e -> e.getKey() + ": " + RenderUtils.fmtDouble2(e.getValue()))
					.collect(Collectors.joining("\n"));

			String response = "```ldif\n" + responseContent + "```";
			if (response.length() < 2000) {
				event.reply(response);
			} else {
				event.replyFile(responseContent.getBytes(), "items.txt");
			}
		} else if (!blueprintStrings.isEmpty() && totalItems.isEmpty()) {
			event.replyIfNoException("I couldn't find any items!");
		} else {
			event.replyIfNoException("No blueprint found!");
		}
	}

	private void handleBlueprintItemsRawCommand(SlashCommandEvent event) throws IOException {

		String content = event.getCommandString();

		Optional<Attachment> attachment = event.optParamAttachment("file");
		if (attachment.isPresent()) {
			content += " " + attachment.get().getUrl();
		}

		List<BSBlueprintString> blueprintStrings = BlueprintFinder.search(content, event.getReporting());

		Map<String, Double> totalItems = new LinkedHashMap<>();
		for (BSBlueprintString bs : blueprintStrings) {
			for (BSBlueprint blueprint : bs.findAllBlueprints()) {
				Map<String, Double> items = FBSR.generateTotalItems(blueprint);
				items.forEach((k, v) -> {
					totalItems.compute(k, ($, old) -> old == null ? v : old + v);
				});
			}
		}

		Map<String, Double> rawItems = FBSR.generateTotalRawItems(totalItems);

		if (!rawItems.isEmpty()) {
			// TODO figure out wiki naming to include modded
//			DataTable table = FactorioData.getTable();
			String responseContent = rawItems.entrySet().stream().sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
//					.map(e -> table.getWikiItemName(e.getKey()) + ": " + RenderUtils.fmtDouble2(e.getValue()))
					.map(e -> e.getKey() + ": " + RenderUtils.fmtDouble2(e.getValue()))
					.collect(Collectors.joining("\n"));

			String response = "```ldif\n" + responseContent + "```";
			if (response.length() < 2000) {
				event.reply(response);
			} else {
				event.replyFile(responseContent.getBytes(), "raw-items.txt");
			}
		} else if (!blueprintStrings.isEmpty() && rawItems.isEmpty()) {
			event.replyIfNoException("I couldn't find any items!");
		} else {
			event.replyIfNoException("No blueprint found!");
		}
	}

	private void handleBlueprintJsonCommand(SlashCommandEvent event) throws JSONException, IOException {
		String content = event.getCommandString();

		Optional<Attachment> attachment = event.optParamAttachment("file");
		if (attachment.isPresent()) {
			content += " " + attachment.get().getUrl();
		}

		List<FindBlueprintResult> results = BlueprintFinder.searchRaw(content, event.getReporting());

		if (results.isEmpty()) {
			event.replyIfNoException("No blueprint string found!");

		} else if (results.size() > 1) {
			event.replyIfNoException("More than one blueprint string was found, please only specify one.");

		} else if (!results.get(0).encoded) {
			event.replyIfNoException("Blueprint is already in json format!");

		} else if (results.get(0).encoded) {
			JSONObject json = BSBlueprintString.decodeRaw(results.get(0).encodedData.get());
			Optional<String> label;
			try {
				label = new BSBlueprintString(json).findFirstLabel();
			} catch (Exception e) {
				label = Optional.empty();
			}
			byte[] bytes = json.toString(2).getBytes();
			event.replyFile(bytes, WebUtils.formatBlueprintFilename(label, ".json"));
		}
	}

	private void handleBlueprintTotalsCommand(SlashCommandEvent event) throws IOException {
		String content = event.getCommandString();

		Optional<Attachment> attachment = event.optParamAttachment("file");
		if (attachment.isPresent()) {
			content += " " + attachment.get().getUrl();
		}

		List<BSBlueprintString> blueprintStrings = BlueprintFinder.search(content, event.getReporting());

		Map<String, Double> totalItems = new LinkedHashMap<>();
		for (BSBlueprintString bs : blueprintStrings) {
			for (BSBlueprint blueprint : bs.findAllBlueprints()) {
				Map<String, Double> items = FBSR.generateSummedTotalItems(blueprint);
				items.forEach((k, v) -> {
					totalItems.compute(k, ($, old) -> old == null ? v : old + v);
				});
			}
		}

		if (!totalItems.isEmpty()) {
			String responseContent = totalItems.entrySet().stream()
					.sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
					.map(e -> e.getKey() + ": " + RenderUtils.fmtDouble2(e.getValue()))
					.collect(Collectors.joining("\n"));

			String response = "```ldif\n" + responseContent + "```";
			if (response.length() < 2000) {
				event.reply(response);
			} else {
				event.replyFile(responseContent.getBytes(), "totals.txt");
			}
		} else if (!blueprintStrings.isEmpty() && totalItems.isEmpty()) {
			event.replyIfNoException("I couldn't find any items!");
		} else {
			event.replyIfNoException("No blueprint found!");
		}
	}

	private void handleBookDirectoryCommand(SlashCommandEvent event) throws IOException {
		String content = event.getCommandString();

		Optional<Attachment> attachment = event.optParamAttachment("file");
		if (attachment.isPresent()) {
			content += " " + attachment.get().getUrl();
		}

		List<BSBlueprintString> blueprintStrings = BlueprintFinder.search(content, event.getReporting());

		try (StringWriter sw = new StringWriter()) {
			for (BSBlueprintString blueprintString : blueprintStrings) {
				handleBookDirectoryCommand_dirWalk(0, false, sw, blueprintString);
			}

			event.reply(sw.toString());
		}
	}

	private void handleBookDirectoryCommand_dirWalk(int level, boolean ending, StringWriter sw,
			BSBlueprintString blueprintString) {
		if (blueprintString.blueprint.isPresent()) {
			sw.append('`');
			for (int i = 0; i < level; i++) {
				sw.append(' ');
				if (i == level - 1) {
					if (ending) {
						sw.append('\u2514'); // single up and single right
					} else {
						sw.append('\u251C'); // single vertical and single right
					}
				} else {
					sw.append('\u2502'); // single vertical
				}
			}
			sw.append('\u2500'); // single horizontal
			sw.append('\u2500'); // single horizontal
			sw.append(' ');
			sw.append(blueprintString.blueprint.get().label.orElse("Untitled Blueprint"));
			sw.append('`');
			sw.append('\n');

		} else if (blueprintString.blueprintBook.isPresent()) {
			BSBlueprintBook book = blueprintString.blueprintBook.get();
			sw.append('`');
			for (int i = 0; i < level; i++) {
				sw.append(' ');
				if (i == level - 1) {
					if (ending && book.blueprints.isEmpty()) {
						sw.append('\u2558'); // single up and double right
					} else {
						sw.append('\u255E'); // single vertical and double right
					}
				} else {
					sw.append('\u2502'); // single vertical
				}
			}
			if (book.blueprints.isEmpty()) {
				sw.append('\u2550'); // double horizontal
				sw.append('\u2550'); // double horizontal
			} else {
				sw.append('\u2550'); // double horizontal
				sw.append('\u2564'); // double horizontal and single down
			}
			sw.append(' ');
			sw.append(book.label.orElse("Untitled Book"));
			sw.append('`');
			sw.append('\n');
			List<BSBlueprintString> blueprints = book.blueprints;
			for (int i = 0; i < blueprints.size(); i++) {
				BSBlueprintString child = blueprints.get(i);
				handleBookDirectoryCommand_dirWalk(level + 1, i == blueprints.size() - 1, sw, child);
			}
		}
	}

	public void onBlueprintContextInteraction(MessageContextInteractionEvent event, CommandReporting reporting)
			throws InterruptedException, ExecutionException, IOException {

		ContextTarget targetType = event.getTargetType();
		if (targetType == ContextTarget.USER) {
			return;
		}

		InteractionHook hook = event.deferReply(true).complete();

		Message target = event.getTarget();

		String content = target.getContentRaw();

		for (Attachment attachment : target.getAttachments()) {
			content += " " + attachment.getUrl();
		}

		Optional<BSBlueprintString> optBlueprintString = BlueprintFinder.search(content, reporting).stream()
				.findFirst();

		if (optBlueprintString.isEmpty()) {
			reporting.addWarning("Blueprint string not found.");
			hook.deleteOriginal().queue();
			return;
		}

		BSBlueprintString blueprintString = optBlueprintString.get();

		List<Long> renderTimes = new ArrayList<>();

		Optional<MessageEmbed> embed = Optional.empty();

		Optional<String> label = null;
		BufferedImage image = null;

		List<List<ItemComponent>> actionRows = new ArrayList<>();
		List<ItemComponent> actionButtonRow = new ArrayList<>();

		Future<Message> futBlueprintStringUpload = useDiscordForFileHosting(
				WebUtils.formatBlueprintFilename(blueprintString.findFirstLabel(), "txt"),
				blueprintString.getRaw().get());

		if (blueprintString.blueprint.isPresent()) {
			BSBlueprint blueprint = blueprintString.blueprint.get();

			GUILayoutBlueprint layout = new GUILayoutBlueprint();
			layout.setBlueprint(blueprint);
			layout.setReporting(reporting);
			image = layout.generateDiscordImage();
			renderTimes.add(layout.getResult().renderTime);

			if (layout.getResult().renderScale < 0.501) {
				actionButtonRow.add(Button.secondary("reply-zoom|" + futBlueprintStringUpload.get().getId(), "Zoom In")
						.withEmoji(EMOJI_SEARCH));
			}

			label = blueprint.label;

		} else if (blueprintString.blueprintBook.isPresent()) {
			BSBlueprintBook book = blueprintString.blueprintBook.get();

			if (book.blueprints.isEmpty()) {
				reporting.addWarning("Blueprint book is empty.");
				hook.deleteOriginal().queue();
				return;
			}

			GUILayoutBook layout = new GUILayoutBook();
			layout.setBook(book);
			layout.setReporting(reporting);
			image = layout.generateDiscordImage();
			renderTimes.add(layout.getResults().stream().mapToLong(r -> r.renderTime).sum());

			List<BSBlueprint> blueprints = book.getAllBlueprints();
			StringSelectMenu.Builder menuBuilder = StringSelectMenu.create("reply-book-blueprint");
			for (int i = 0; i < blueprints.size(); i++) {
				if (i < 25) {
					BSBlueprint blueprint = blueprints.get(i);
					menuBuilder.addOption(blueprint.label.orElse("Untitled Blueprint " + (i + 1)),
							futBlueprintStringUpload.get().getId() + "|" + i);
				}
				// TODO figure out how to handle more than 25 blueprint options
			}
			actionRows.add(ImmutableList.of(menuBuilder.build()));
			// TODO each blueprint renders and upload

			label = book.label;

		} else if (blueprintString.deconstructionPlanner.isPresent()) {
			EmbedBuilder builder = new EmbedBuilder();
			BSDeconstructionPlanner planner = blueprintString.deconstructionPlanner.get();
			builder.setColor(Color.darkGray);
			builder.setDescription("Blueprint string is a deconstruction planner.");
			if (planner.label.isPresent()) {
				builder.addField(new Field("Label", planner.label.get(), false));
			}
			if (planner.description.isPresent()) {
				builder.addField(new Field("Description", planner.description.get(), false));
			}
			embed = Optional.of(builder.build());
			// TODO more details from deconstruction planner

		} else if (blueprintString.upgradePlanner.isPresent()) {
			EmbedBuilder builder = new EmbedBuilder();
			BSUpgradePlanner planner = blueprintString.upgradePlanner.get();
			builder.setColor(Color.darkGray);
			builder.setDescription("Blueprint string is an upgrade planner.");
			if (planner.label.isPresent()) {
				builder.addField(new Field("Label", planner.label.get(), false));
			}
			if (planner.description.isPresent()) {
				builder.addField(new Field("Description", planner.description.get(), false));
			}
			embed = Optional.of(builder.build());
			// TODO more details from upgrade planner
		}

		Emoji emoji = EMOJI_BLUEPRINT;
		if (blueprintString.blueprintBook.isPresent()) {
			emoji = EMOJI_BLUEPRINTBOOK;
		} else if (blueprintString.deconstructionPlanner.isPresent()) {
			emoji = EMOJI_DECONSTRUCTIONPLANNER;
		} else if (blueprintString.upgradePlanner.isPresent()) {
			emoji = EMOJI_UPGRADEPLANNER;
		}

		String actionId = "reply-blueprint|" + futBlueprintStringUpload.get().getId()
				+ blueprintString.findFirstLabel().map(s -> "|" + s).orElse("");
		if (actionId.length() > 100) {
			actionId = actionId.substring(0, 100);// XXX need a better solution
		}
		actionButtonRow.add(Button.secondary(actionId, "Download").withEmoji(emoji));

		if (!renderTimes.isEmpty()) {
			String renderTimesStr = renderTimes.stream().mapToLong(l1 -> l1).sum() + " ms" + (renderTimes.size() > 1
					? (" [" + renderTimes.stream().map(Object::toString).collect(Collectors.joining(", ")) + "]")
					: "");
			if (renderTimesStr.length() > 256) {
				renderTimesStr = renderTimesStr.substring(0, 256) + "...";
			}
			reporting.addField(new Field("Render Time", renderTimesStr, true));
		}

		if (!actionButtonRow.isEmpty()) {
			actionRows.add(actionButtonRow);
		}

		MessageCreateAction reply;
		if (embed.isPresent()) {
			reply = target.replyEmbeds(embed.get());
		} else {
			ImageShrinkResult shrinkResult = shrinkImageToFitUploadLimit(image);
			String imageFilename = WebUtils.formatBlueprintFilename(label, shrinkResult.extension);
			reply = target.replyFiles(FileUpload.fromData(shrinkResult.data, imageFilename));
		}

		for (List<ItemComponent> actionRow : actionRows) {
			if (!actionRow.isEmpty()) {
				reply.addActionRow(actionRow);
			}
		}

		Message message = reply.complete();
		reporting.addReply(message);
		hook.deleteOriginal().queue();
	}

	public void onButtonInteraction(ButtonInteractionEvent event, CommandReporting reporting)
			throws IOException, InterruptedException, ExecutionException {
		String raw = event.getComponentId();
		String[] split = raw.split("\\|");
		String command = split[0];

		InteractionHook hook = event.deferReply(true).complete();

		String replyContent = null;

		if (command.equals("reply-blueprint")) {
			String messageId = split[1];
			Optional<String> label = split.length > 2 ? Optional.of(split[2]) : Optional.empty();

			TextChannel hostingChannel = bot.getJDA().getTextChannelById(hostingChannelID);
			Message message = hostingChannel.retrieveMessageById(messageId).complete();
			replyContent = label.map(s -> s + " ").orElse("") + message.getAttachments().get(0).getUrl();

		} else if (command.equals("reply-zoom")) {

			String cacheKey = raw;
			CachedMessageImageResult cachedResult = recentLazyLoadedMessages.getIfPresent(cacheKey);

			if (cachedResult != null) {
				TextChannel hostingChannel = bot.getJDA().getTextChannelById(hostingChannelID);
				Message messageImage = hostingChannel.retrieveMessageById(cachedResult.messageId).complete();

				replyContent = cachedResult.label.map(s -> s + " ").orElse("")
						+ messageImage.getAttachments().get(0).getUrl();

			} else {
				String messageId = split[1];

				TextChannel hostingChannel = bot.getJDA().getTextChannelById(hostingChannelID);
				Message message = hostingChannel.retrieveMessageById(messageId).complete();

				BSBlueprintString blueprintString = BlueprintFinder
						.search(message.getAttachments().get(0).getUrl(), reporting).get(0);
				BSBlueprint blueprint = blueprintString.blueprint.get();

				RenderRequest request = new RenderRequest(blueprint, reporting);
				RenderResult result = FBSR.renderBlueprint(request);

				reporting.addField(new Field("Render Time", result.renderTime + " ms", true));

				ImageShrinkResult shrinkResult = shrinkImageToFitUploadLimit(result.image);
				String imageFilename = WebUtils.formatBlueprintFilename(blueprint.label, shrinkResult.extension);

				Message messageImage = useDiscordForFileHosting(imageFilename, shrinkResult.data).get();

				recentLazyLoadedMessages.put(cacheKey,
						new CachedMessageImageResult(blueprint.label, messageImage.getId()));

				replyContent = blueprint.label.map(s -> s + " ").orElse("")
						+ messageImage.getAttachments().get(0).getUrl();
			}

		} else {
			System.err.println("UNKNOWN COMMAND " + command);
			event.reply("Unknown Command: " + command).setEphemeral(true).queue();
		}

		if (replyContent != null) {
			if (event.getChannelType() != ChannelType.PRIVATE) {
				hook.sendMessage(replyContent).queue();
			} else {
				hook.deleteOriginal().queue();
			}

			try {
				PrivateChannel privateChannel = event.getUser().openPrivateChannel().complete();
				Message replyMessage = privateChannel
						.sendMessage(event.getMessage().getJumpUrl() + "\n====> " + replyContent).complete();
				reporting.addReply(replyMessage);
			} catch (ErrorResponseException e) {
				if (e.getErrorResponse() != ErrorResponse.CANNOT_SEND_TO_USER) {
					throw e;
				}
			}
		}
	}

	public void onSelectionInteraction(StringSelectInteractionEvent event, CommandReporting reporting)
			throws InterruptedException, ExecutionException, IOException {
		String command = event.getComponentId();

		InteractionHook hook = event.deferReply(true).complete();

		String replyContent = null;

		if (command.equals("reply-book-blueprint")) {
			String raw = event.getValues().get(0);

			String cacheKey = command + "|" + raw;
			CachedMessageImageResult cachedResult = recentLazyLoadedMessages.getIfPresent(cacheKey);

			if (cachedResult != null) {
				TextChannel hostingChannel = bot.getJDA().getTextChannelById(hostingChannelID);
				Message messageImage = hostingChannel.retrieveMessageById(cachedResult.messageId).complete();

				replyContent = cachedResult.label.map(s -> s + " ").orElse("")
						+ messageImage.getAttachments().get(0).getUrl();

			} else {

				String[] split = raw.split("\\|");
				String messageId = split[0];
				int index = Integer.parseInt(split[1]);

				TextChannel hostingChannel = bot.getJDA().getTextChannelById(hostingChannelID);
				Message message = hostingChannel.retrieveMessageById(messageId).complete();

				BSBlueprintString blueprintString = BlueprintFinder
						.search(message.getAttachments().get(0).getUrl(), reporting).get(0);
				BSBlueprint blueprint = blueprintString.blueprintBook.get().getAllBlueprints().get(index);

				RenderRequest request = new RenderRequest(blueprint, reporting);
				RenderResult result = FBSR.renderBlueprint(request);

				reporting.addField(new Field("Render Time", result.renderTime + " ms", true));

				ImageShrinkResult shrinkResult = shrinkImageToFitUploadLimit(result.image);
				String imageFilename = WebUtils.formatBlueprintFilename(blueprint.label, shrinkResult.extension);

				Message messageImage = useDiscordForFileHosting(imageFilename, shrinkResult.data).get();

				recentLazyLoadedMessages.put(cacheKey,
						new CachedMessageImageResult(blueprint.label, messageImage.getId()));

				replyContent = blueprint.label.map(s -> s + " ").orElse("")
						+ messageImage.getAttachments().get(0).getUrl();

			}

		} else {
			System.out.println("UNKNOWN COMMAND " + command);
			event.reply("Unknown Command: " + command).setEphemeral(true).queue();
		}

		if (replyContent != null) {
			if (event.getChannelType() != ChannelType.PRIVATE) {
				hook.sendMessage(replyContent).queue();
			} else {
				hook.deleteOriginal().queue();
			}

			try {
				PrivateChannel privateChannel = event.getUser().openPrivateChannel().complete();
				Message replyMessage = privateChannel
						.sendMessage(event.getMessage().getJumpUrl() + "\n====> " + replyContent).complete();
				reporting.addReply(replyMessage);
			} catch (ErrorResponseException e) {
				if (e.getErrorResponse() != ErrorResponse.CANNOT_SEND_TO_USER) {
					throw e;
				}
			}
		}
	}

	private void sendLuaDumpFile(EventReply event, String category, String name, LuaValue lua) throws IOException {
		JSONObject json = new JSONObject();
		Utils.terribleHackToHaveOrderedJSONObject(json);
		json.put("name", name);
		json.put("category", category);
		json.put("version", FBSR.getVersion());
		json.put("data", Utils.<JSONObject>convertLuaToJson(lua));
		String fileName = category + "_" + name + "_dump_" + FBSR.getVersion() + ".json";
//		String url = WebUtils.uploadToHostingService(fileName, json.toString(2).getBytes());
//		event.reply(category + " " + name + " lua dump: [" + fileName + "](" + url + ")");
		event.replyFile(json.toString(2).getBytes(), fileName);
	}

	@Override
	protected void shutDown() {
		ServiceFinder.removeService(this);
		ServiceFinder.removeService(WatchdogReporter.class);
		bot.stopAsync().awaitTerminated();
	}

	@Override
	protected void startUp() throws JSONException, IOException {
		configJson = Config.get().getJSONObject("discord");

		FBSR.initialize();
		System.out.println("Factorio " + FBSR.getVersion() + " Data Loaded.");

		bot = DCBA.builder()//
				.setInfo("Blueprint Bot")//
				.withSupport(
						"Find Demod and complain to him!\nYou can find him over in the [Factorio Discord.](https://discord.gg/factorio)")//
				.withTechnology("[FBSR](https://github.com/demodude4u/Factorio-FBSR)",
						"Factorio Blueprint String Renderer")//
				.withTechnology("[FactorioDataWrapper](https://github.com/demodude4u/Java-Factorio-Data-Wrapper)",
						"Factorio Data Scraper")//
				.withCredits("Attribution", "[Factorio](https://www.factorio.com/) - Made by Wube Software")//
				.withCredits("Contributors", "Demod")//
				.withCredits("Contributors", "Bilka")//
				.withCredits("Contributors", "FactorioBlueprints")//
				.withCredits("Contributors", "acid")//
				.withCredits("Contributors", "Vilsol")//
				.withCredits("Special Thanks", "AntiElitz")//
				.withCredits("Special Thanks", "Members of Team Steelaxe")//
				.withVersion("Multiverse " + FBSR.getVersion())
				.withCustomField("Mods Loaded",
						FactorioManager.getDatas().stream().flatMap(d -> d.getModLoader().getMods().keySet().stream())
								.distinct().sorted(Comparator.comparing(s -> s.toLowerCase()))
								.collect(Collectors.joining(", ")))
				.addSlashCommand("bp/string", "Renders an image of the blueprint string.",
						event -> handleBlueprintCommand(event))//
				.withParam(OptionType.STRING, "string", "Blueprint string.")//
				//
				.addSlashCommand("bp/url", "Renders an image of the blueprint url.",
						event -> handleBlueprintCommand(event))//
				.withParam(OptionType.STRING, "url", "Url containing blueprint string.")//
				//
				.addSlashCommand("bp/file", "Renders an image of the blueprint attachment.",
						event -> handleBlueprintCommand(event))//
				.withParam(OptionType.ATTACHMENT, "file", "File containing blueprint string.")//
				//
				.addSlashCommand("blueprint/string", "Renders an image of the blueprint string.",
						event -> handleBlueprintCommand(event))//
				.withParam(OptionType.STRING, "string", "Blueprint string.")//
				//
				.addSlashCommand("blueprint/url", "Renders an image of the blueprint url.",
						event -> handleBlueprintCommand(event))//
				.withParam(OptionType.STRING, "url", "Url containing blueprint string.")//
				//
				.addSlashCommand("blueprint/file", "Renders an image of the blueprint attachment.",
						event -> handleBlueprintCommand(event))//
				.withParam(OptionType.ATTACHMENT, "file", "File containing blueprint string.")//
				//
				.addSlashCommand("blueprint/custom", "Alternative rendering options to display a blueprint.",
						event -> handleBlueprintCustomCommand(event))//
				.withOptionalParam(OptionType.STRING, "string", "Blueprint string.")//
				.withOptionalParam(OptionType.STRING, "url", "Url containing blueprint string.")//
				.withOptionalParam(OptionType.ATTACHMENT, "file", "File containing blueprint string.")//
				.withOptionalParam(OptionType.STRING, "book-filter",
						"If the blueprint is a book, specify blueprint label matching this filter.")//
				.withOptionalParam(OptionType.INTEGER, "book-index",
						"If the blueprint is a book, specify blueprint at index.")//
				.withOptionalParam(OptionType.INTEGER, "min-width", "Minimum width of image, in pixels.")//
				.withOptionalParam(OptionType.INTEGER, "min-height", "Minimum height of image, in pixels.")//
				.withOptionalParam(OptionType.INTEGER, "max-width", "Maximum width of image, in pixels.")//
				.withOptionalParam(OptionType.INTEGER, "max-height", "Maximum height of image, in pixels.")//
				.withOptionalParam(OptionType.NUMBER, "max-scale", "Maximum scale of entities.")//
				.withOptionalParam(OptionType.BOOLEAN, "show-background", "Show background.")//
				.withOptionalParam(OptionType.BOOLEAN, "show-gridlines", "Show grid lines.")//
				.withOptionalParam(OptionType.BOOLEAN, "show-alt-mode",
						"Show alt mode information, like filters and indicators.")//
				.withOptionalParam(OptionType.BOOLEAN, "show-path-outputs",
						"Show item path coloring coming from crafting or filtered outputs.")//
				.withOptionalParam(OptionType.BOOLEAN, "show-path-inputs",
						"Show item path coloring leading to crafting or filtered inputs.")//
				.withOptionalParam(OptionType.BOOLEAN, "show-path-rails", "Show rail station path coloring.")//
				.withOptionalParam(OptionType.BOOLEAN, "show-grid-numbers", "Show grid numbers.")//
				.withOptionalParam(OptionType.BOOLEAN, "show-grid-above-belts", "Show grid lines over the belts.")//
				.withOptionalParam(OptionType.BOOLEAN, "debug-path-items", "Show debug markers for item pathing.")//
				.withOptionalParam(OptionType.BOOLEAN, "debug-path-rails", "Show debug markers for rail pathing.")//
				.withOptionalParam(OptionType.BOOLEAN, "debug-entity-placement",
						"Show debug markers for entity position, direction, and bounds.")//
				//
				.addSlashCommand("json", "Provides a dump of the json data in the specified blueprint string.",
						event -> handleBlueprintJsonCommand(event))//
				.withOptionalParam(OptionType.STRING, "string", "Blueprint string.")//
				.withOptionalParam(OptionType.STRING, "url", "Url containing blueprint string.")//
				.withOptionalParam(OptionType.ATTACHMENT, "file", "File containing blueprint string.")//
				//
				.addSlashCommand("items", "Prints out all of the items needed by the blueprint.",
						event -> handleBlueprintItemsCommand(event))//
				.withOptionalParam(OptionType.STRING, "string", "Blueprint string.")//
				.withOptionalParam(OptionType.STRING, "url", "Url containing blueprint string.")//
				.withOptionalParam(OptionType.ATTACHMENT, "file", "File containing blueprint string.")//
				//
				.addSlashCommand("raw/items", "Prints out all of the raw items needed by the blueprint.",
						event -> handleBlueprintItemsRawCommand(event))//
				.withOptionalParam(OptionType.STRING, "string", "Blueprint string.")//
				.withOptionalParam(OptionType.STRING, "url", "Url containing blueprint string.")//
				.withOptionalParam(OptionType.ATTACHMENT, "file", "File containing blueprint string.")//
				//
				.addSlashCommand("counts",
						"Prints out the total counts of entities, items and tiles needed by the blueprint.",
						event -> handleBlueprintTotalsCommand(event))
				.withOptionalParam(OptionType.STRING, "string", "Blueprint string.")//
				.withOptionalParam(OptionType.STRING, "url", "Url containing blueprint string.")//
				.withOptionalParam(OptionType.ATTACHMENT, "file", "File containing blueprint string.")//
				//
				.addSlashCommand("book/directory", "Prints out list of all blueprints in a blueprint book.",
						event -> handleBookDirectoryCommand(event))
				.withOptionalParam(OptionType.STRING, "string", "Blueprint string.")//
				.withOptionalParam(OptionType.STRING, "url", "Url containing blueprint string.")//
				.withOptionalParam(OptionType.ATTACHMENT, "file", "File containing blueprint string.")//
				//
				.addSlashCommand("prototype/entity", "Lua data for the specified entity prototype.",
						createPrototypeCommandHandler("entity", FactorioManager.getEntities()),
						createPrototypeAutoCompleteHandler(FactorioManager.getEntities()))//
				.withAutoParam(OptionType.STRING, "name", "Prototype name of the entity.")//
				//
				.addSlashCommand("prototype/recipe", "Lua data for the specified recipe prototype.",
						createPrototypeCommandHandler("recipe", FactorioManager.getRecipes()),
						createPrototypeAutoCompleteHandler(FactorioManager.getRecipes()))//
				.withAutoParam(OptionType.STRING, "name", "Prototype name of the recipe.")//
				//
				.addSlashCommand("prototype/fluid", "Lua data for the specified fluid prototype.",
						createPrototypeCommandHandler("fluid", FactorioManager.getFluids()),
						createPrototypeAutoCompleteHandler(FactorioManager.getFluids()))//
				.withAutoParam(OptionType.STRING, "name", "Prototype name of the fluid.")//
				//
				.addSlashCommand("prototype/item", "Lua data for the specified item prototype.",
						createPrototypeCommandHandler("item", FactorioManager.getItems()),
						createPrototypeAutoCompleteHandler(FactorioManager.getItems()))//
				.withAutoParam(OptionType.STRING, "name", "Prototype name of the item.")//
				//
				.addSlashCommand("prototype/technology", "Lua data for the specified technology prototype.",
						createPrototypeCommandHandler("technology", FactorioManager.getTechnologies()),
						createPrototypeAutoCompleteHandler(FactorioManager.getTechnologies()))//
				.withAutoParam(OptionType.STRING, "name", "Prototype name of the technology.")//
				//
				.addSlashCommand("prototype/equipment", "Lua data for the specified equipment prototype.",
						createPrototypeCommandHandler("equipment", FactorioManager.getEquipments()),
						createPrototypeAutoCompleteHandler(FactorioManager.getEquipments()))//
				.withAutoParam(OptionType.STRING, "name", "Prototype name of the equipment.")//
				//
				.addSlashCommand("prototype/tile", "Lua data for the specified tile prototype.",
						createPrototypeCommandHandler("tile", FactorioManager.getTiles()),
						createPrototypeAutoCompleteHandler(FactorioManager.getTiles()))//
				.withAutoParam(OptionType.STRING, "name", "Prototype name of the tile.")//
				//
				//
				// TODO bring back data.raw, but how?
//				.addSlashCommand("data/raw", "Lua from `data.raw` for the specified key.",
//						createDataRawCommandHandler(table::getRaw))//
				.withParam(OptionType.STRING, "path", "Path to identify which key.")//
				//
				//
				.addButtonHandler(this::onButtonInteraction)//
				.addStringSelectHandler(this::onSelectionInteraction)//
				.setMessageContextHandler("Generate Blueprint Image", this::onBlueprintContextInteraction)
				//
				//
				.withCustomSetup(builder -> {
					return builder//
							.setChunkingFilter(ChunkingFilter.NONE)//
//							.setMemberCachePolicy(MemberCachePolicy.NONE)//
//							.setDisabledIntents(GatewayIntent.GUILD_PRESENCES, GatewayIntent.GUILD_MESSAGE_TYPING,
//									GatewayIntent.GUILD_MEMBERS)//
					;
				}).create();

		bot.startAsync().awaitRunning();

		hostingChannelID = configJson.getString("hosting_channel_id");

		ServiceFinder.addService(this);
		ServiceFinder.addService(WatchdogReporter.class, new WatchdogReporter() {
			@Override
			public void notifyInactive(String label) {
				CommandReporting reporting = new CommandReporting("Watchdog Reporter", null, null);
				reporting.addWarning(label + " has gone inactive!");
				bot.submitReport(reporting);
			}

			@Override
			public void notifyReactive(String label) {
				CommandReporting reporting = new CommandReporting("Watchdog Reporter", null, null);
				reporting.addWarning(label + " is now active again!");
				bot.submitReport(reporting);
			}
		});
	}

	public Future<Message> useDiscordForFileHosting(String filename, BufferedImage image) throws IOException {
		return useDiscordForFileHosting(filename, WebUtils.getImageData(image));
	}

	public Future<Message> useDiscordForFileHosting(String fileName, byte[] fileData) throws IOException {
		TextChannel channel = bot.getJDA().getTextChannelById(hostingChannelID);
		return channel.sendFiles(FileUpload.fromData(fileData, fileName)).submit();
	}

	public Future<Message> useDiscordForFileHosting(String fileName, String content) throws IOException {
		return useDiscordForFileHosting(fileName, content.getBytes(StandardCharsets.UTF_8));
	}
}
