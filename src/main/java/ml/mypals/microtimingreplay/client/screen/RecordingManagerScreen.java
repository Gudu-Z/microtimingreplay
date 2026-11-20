package ml.mypals.microtimingreplay.client.screen;

import ml.mypals.microtimingreplay.client.MTRClientNetworking;
import ml.mypals.microtimingreplay.network.MTRPayloads;
import ml.mypals.microtimingreplay.util.MTRComponent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/** Browse recordings without changing the player's dye target until they choose Edit areas. */
@Environment(EnvType.CLIENT)
public class RecordingManagerScreen extends Screen {
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_TOP = 49;
    private static final int GAP = 6;

    private MTRPayloads.ManagerS2C state;
    private String selected = "";
    private int left, right, leftWidth, rightWidth, controlsY;
    private Button edit, create, record, step;
    private EditBox nameBox, ticksBox;
    private Page profiles, areas;
    private int refreshTicks;
    private Component feedback = Component.empty();
    private boolean success = true;
    // A reply from a closed screen must not match a request from its replacement.
    private static long nextRequestId;
    private long inFlight;
    private enum Pending { NONE, CREATE, HIGHLIGHT, OTHER }
    private Pending pending = Pending.NONE;

    public RecordingManagerScreen() {
        super(label("title", "Recordings"));
    }

    private static Component label(String key, String fallback, Object... args) {
        return MTRComponent.translatable("mtr.manager." + key, fallback, args);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        String oldName = nameBox == null ? "" : nameBox.getValue();
        String oldTicks = ticksBox == null ? "20" : ticksBox.getValue();
        int content = Math.min(570, width - 12);
        left = (width - content) / 2;
        leftWidth = (content - GAP) / 2;
        right = left + leftWidth + GAP;
        rightWidth = content - leftWidth - GAP;
        controlsY = height - 92;
        int count = Math.max(1, (controlsY - ROW_TOP - 3) / ROW_HEIGHT);

        button(right + rightWidth - 55, 5, 55, CommonComponents.GUI_DONE, b -> onClose());
        profiles = new Page(left, leftWidth, count, i -> browse(state.profiles().get(i)));
        areas = new Page(right, rightWidth, count, i -> highlight(state.areas().get(i)));
        int createWidth = Math.min(62, leftWidth / 3);
        nameBox = new EditBox(font, left, controlsY, leftWidth - createWidth - 3, 18, label("name", "Profile name"));
        nameBox.setMaxLength(64);
        nameBox.setHint(label("name", "Profile name"));
        nameBox.setValue(oldName);
        addRenderableWidget(nameBox);
        create = button(left + leftWidth - createWidth, controlsY, createWidth, label("create", "Create"),
                b -> createProfile());
        edit = button(right, controlsY, rightWidth, label("edit", "Edit areas"), b -> editProfile());

        int y = height - 24;
        int labelWidth = font.width(label("ticks", "Ticks")) + 7;
        int fieldWidth = Math.min(62, content / 6);
        int controlsStart = left + labelWidth + fieldWidth + GAP;
        int actionWidth = (left + content - controlsStart - GAP) / 2;
        ticksBox = new EditBox(font, left + labelWidth, y, fieldWidth, 18, label("ticks", "Ticks"));
        ticksBox.setMaxLength(10);
        ticksBox.setFilter(s -> s.chars().allMatch(c -> c >= '0' && c <= '9'));
        ticksBox.setValue(oldTicks);
        addRenderableWidget(ticksBox);
        record = button(controlsStart, y, actionWidth, label("start", "Record"), b -> toggleRecording());
        step = button(controlsStart + actionWidth + GAP, y, left + content - controlsStart - actionWidth - GAP,
                label("step", "Step world 1 tick"), b -> stepWorld());
        nameBox.setResponder(s -> updateControls());
        ticksBox.setResponder(s -> updateControls());
        updateControls();
        if (inFlight == 0) requestSnapshot();
    }

    private Button button(int x, int y, int w, Component text, Button.OnPress action) {
        return addRenderableWidget(Button.builder(text, action).bounds(x, y, w, 18).build());
    }

    private void requestSnapshot() {
        inFlight = ++nextRequestId;
        MTRClientNetworking.requestManager(inFlight, selected);
    }

    @Override
    public void tick() {
        if (++refreshTicks >= 20 && inFlight == 0) {
            refreshTicks = 0;
            requestSnapshot();
        }
    }

    public void update(MTRPayloads.ManagerS2C snapshot) {
        if (snapshot.requestId() != inFlight) return;
        Pending completed = pending;
        pending = Pending.NONE;
        inFlight = 0;
        state = snapshot;
        selected = snapshot.selected();
        if (completed != Pending.NONE) {
            feedback = snapshot.feedback();
            success = snapshot.success();
            if (success && completed == Pending.CREATE) nameBox.setValue("");
            if (success && completed == Pending.HIGHLIGHT) {
                onClose();
                return;
            }
        }
        updateControls();
    }

    private long beginAction(Pending type) {
        pending = type;
        inFlight = ++nextRequestId;
        updateControls();
        return inFlight;
    }

    private void createProfile() {
        MTRClientNetworking.createManagedProfile(beginAction(Pending.CREATE), nameBox.getValue());
    }

    private void editProfile() {
        MTRClientNetworking.editManagedProfile(beginAction(Pending.OTHER), selected, selected.equals(state.editingProfile()));
    }

    private void highlight(MTRPayloads.ManagerArea area) {
        MTRClientNetworking.highlightManagedArea(beginAction(Pending.HIGHLIGHT), selected, area.name());
    }

    private void toggleRecording() {
        long id = beginAction(Pending.OTHER);
        if (state.recordingProfile().isEmpty()) MTRClientNetworking.startManagedRecording(id, selected, duration());
        else MTRClientNetworking.stopManagedRecording(id, selected);
    }

    private void stepWorld() {
        MTRClientNetworking.stepManagedWorld(beginAction(Pending.OTHER), selected);
    }

    private int duration() {
        try {
            return Integer.parseInt(ticksBox.getValue());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void browse(String name) {
        if (selected.equals(name)) return;
        selected = name;
        feedback = Component.empty();
        areas.offset = 0;
        updateControls();
        requestSnapshot();
    }

    private void updateControls() {
        boolean allowed = state != null && state.allowed();
        boolean ready = allowed && pending == Pending.NONE;
        boolean loaded = allowed && !selected.isEmpty() && selected.equals(state.selected());
        boolean recording = allowed && !state.recordingProfile().isEmpty();
        boolean editing = loaded && selected.equals(state.editingProfile());
        List<String> names = allowed ? state.profiles() : List.of();
        profiles.update(names.size());
        for (int i = 0; i < profiles.rows.size() && profiles.offset + i < names.size(); i++) {
            String name = names.get(profiles.offset + i);
            profiles.rows.get(i).active = ready;
            profiles.rows.get(i).setMessage(Component.literal(name).withStyle(name.equals(selected)
                    ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
        }
        areas.update(loaded ? state.areas().size() : 0);
        if (loaded) {
            for (int i = 0; i < areas.rows.size() && areas.offset + i < state.areas().size(); i++) {
                var area = state.areas().get(areas.offset + i);
                var row = areas.rows.get(i);
                row.active = ready;
                row.setMessage(Component.literal(area.name() + " · " + area.dimension().replaceFirst("^minecraft:", "")));
                row.setTooltip(Tooltip.create(label("area_tip", "Highlight %s in %s (%s → %s)",
                        area.name(), area.dimension(), area.from().toShortString(), area.to().toShortString())));
            }
        }
        nameBox.setEditable(ready);
        String name = nameBox.getValue();
        create.active = ready && !name.isEmpty() && !names.contains(name);
        edit.setMessage(editing ? label("end_edit", "Finish editing") : label("edit", "Edit areas"));
        edit.active = ready && loaded;
        ticksBox.setEditable(ready && !recording);
        record.setMessage(recording ? label("stop", "Stop") : label("start", "Record"));
        record.active = ready && loaded && (recording ? selected.equals(state.recordingProfile()) : duration() > 0);
        step.active = ready && state.canStep();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        text(g, title, left, 10, leftWidth, MTRWidgets.TEXT);
        text(g, label("profiles", "Profiles"), left, 33, leftWidth - 42, MTRWidgets.TEXT);
        Component areaHeader = selected.isEmpty() ? label("areas_empty_title", "Areas")
                : label("areas", "Areas in %s", selected);
        text(g, areaHeader, right, 33, rightWidth - 42, MTRWidgets.TEXT);

        if (state == null || !state.allowed()) {
            text(g, state == null ? label("waiting", "Loading…")
                    : label("denied", "You need permission to manage recordings"), left, ROW_TOP + 4,
                    leftWidth + GAP + rightWidth, MTRWidgets.TEXT_DIM);
        } else {
            if (state.profiles().isEmpty()) {
                text(g, label("no_profiles", "No profiles yet"), left, ROW_TOP + 4, leftWidth, MTRWidgets.TEXT_DIM);
            }
            if (!selected.isEmpty() && selected.equals(state.selected()) && state.areas().isEmpty()) {
                text(g, label("no_areas", "No saved areas"), right, ROW_TOP + 4, rightWidth, MTRWidgets.TEXT_DIM);
            }
            Component target = state.editingProfile().isEmpty() ? label("no_edit", "No profile being edited")
                    : label("editing", "Editing %s", state.editingProfile());
            text(g, target, left, height - 67, leftWidth, MTRWidgets.TEXT_DIM);
            Component status = state.frozen() ? label("frozen", "Frozen") : label("running", "Running");
            text(g, status, right, height - 67, rightWidth, state.frozen() ? MTRWidgets.TEXT_ACCENT : MTRWidgets.TEXT_ON);
            if (!state.recordingProfile().isEmpty()) {
                text(g, label("progress", "%s · %s/%s ticks", state.recordingProfile(),
                        state.completedTicks(), state.targetTicks()), left, height - 53, leftWidth + rightWidth, MTRWidgets.TEXT);
            }
        }
        int feedbackWidth = leftWidth + GAP + rightWidth;
        text(g, feedback, left, height - 39, feedbackWidth, success ? MTRWidgets.TEXT_ACCENT : MTRWidgets.TEXT_OFF);
        if (font.width(feedback) > feedbackWidth && MTRWidgets.isOver(mouseX, mouseY, left, height - 40, feedbackWidth, 13)) {
            g.setTooltipForNextFrame(font, feedback, mouseX, mouseY);
        }
        text(g, label("ticks", "Ticks"), left, height - 19, ticksBox.getX() - left - 2, MTRWidgets.TEXT);
    }

    private void text(GuiGraphics g, Component text, int x, int y, int w, int color) {
        g.drawString(font, font.plainSubstrByWidth(text.getString(), Math.max(0, w)), x, y, color);
    }

    /** Paging buttons also make every list entry reachable using only the keyboard. */
    private final class Page {
        final int x, w;
        final List<Button> rows = new ArrayList<>();
        final Button prev, next;
        int offset;

        Page(int x, int w, int count, IntConsumer onRow) {
            this.x = x;
            this.w = w;
            prev = button(x + w - 39, 26, 18, Component.literal("‹"), b -> scroll(-rows.size()));
            next = button(x + w - 18, 26, 18, Component.literal("›"), b -> scroll(rows.size()));
            prev.setTooltip(Tooltip.create(label("prev", "Previous page")));
            next.setTooltip(Tooltip.create(label("next", "Next page")));
            for (int i = 0; i < count; i++) {
                int row = i;
                rows.add(button(x, ROW_TOP + i * ROW_HEIGHT, w, Component.empty(), b -> onRow.accept(offset + row)));
            }
        }

        void update(int size) {
            offset = Math.clamp(offset, 0, Math.max(0, size - rows.size()));
            prev.active = offset > 0;
            next.active = offset + rows.size() < size;
            for (int i = 0; i < rows.size(); i++) rows.get(i).visible = offset + i < size;
        }

        void scroll(int delta) {
            offset += delta;
            updateControls();
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + w && mouseY >= 26 && mouseY < controlsY;
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        Page hovered = profiles.contains(x, y) ? profiles : areas.contains(x, y) ? areas : null;
        if (hovered == null || dy == 0) return super.mouseScrolled(x, y, dx, dy);
        hovered.scroll(-(int) Math.signum(dy));
        return true;
    }
}
