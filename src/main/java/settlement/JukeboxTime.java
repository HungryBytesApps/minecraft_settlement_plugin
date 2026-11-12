package settlement;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Free time with the jukebox: a settler fetches a music disc from storage (a chest settlers may take
 * from), puts it on, and listens a while (nodding along). A disc that has finished is taken out and
 * brought back to storage.
 */
final class JukeboxTime {
    private JukeboxTime() {}

    static boolean isDisc(Material m) {
        return m.name().startsWith("MUSIC_DISC_");
    }

    /** Something to do with a jukebox now, or null. */
    static Activity start(Worker w) {
        Town t = w.town();
        if (t == null) return null;
        List<Block> boxes = Fields.jukeboxes(t, w.plugin.settings().townRadius());
        if (boxes.isEmpty()) return null;
        // A finished disc: take it out and bring it back.
        for (Block b : boxes) {
            if (b.getState(false) instanceof Jukebox j && j.hasRecord() && !j.isPlaying() && !Work.taken(w, b)) {
                Work.claim(w, b);
                return new GoAndDo(w, b, 2.0, "Putting the record away", x -> x.getType() == Material.JUKEBOX, x -> {
                    if (!(x.getState(false) instanceof Jukebox jb) || !jb.hasRecord() || jb.isPlaying()) return;
                    ItemStack disc = jb.getRecord().clone();
                    jb.setRecord(null);
                    w.pockets.addItem(disc);
                    x.getWorld().playSound(x.getLocation().add(0.5, 0.5, 0.5), "minecraft:block.wooden_button.click_on", SoundCategory.BLOCKS, 0.6f, 1f);
                });
            }
        }
        // A disc in the pockets (taken out before): back to storage.
        if (carriesDisc(w)) return Work.deposit(w, t, m -> !isDisc(m));
        // An empty jukebox and a disc in storage: put one on.
        Block box = null;
        for (Block b : boxes) {
            if (b.getState(false) instanceof Jukebox j && !j.hasRecord() && !Work.taken(w, b)) {
                box = b;
                break;
            }
        }
        if (box == null || !Work.inStorage(w, t, JukeboxTime::isDisc)) return null;
        Work.claim(w, box);
        return new PutOn(w, t, box);
    }

    private static boolean carriesDisc(Worker w) {
        for (ItemStack it : w.pockets.getContents()) if (it != null && isDisc(it.getType())) return true;
        return false;
    }

    /** Fetch a disc, put it on, listen for a while. */
    private static final class PutOn implements Activity {
        private final Worker w;
        private final Town t;
        private final Block box;
        private Activity step;
        private int phase, listen;

        PutOn(Worker w, Town t, Block box) {
            this.w = w;
            this.t = t;
            this.box = box;
        }

        @Override
        public boolean tick() {
            if (step != null) {
                if (!step.tick()) return false;
                step.stop();
                step = null;
                phase++;
            }
            switch (phase) {
                case 0 -> {
                    // Pick a random disc from storage.
                    step = Work.fetch(w, t, JukeboxTime::isDisc, 1, "Getting a record");
                    if (step == null) return true;
                }
                case 1 -> {
                    if (!carriesDisc(w)) return true;
                    step = new GoAndDo(w, box, 2.0, "Putting on a record", b -> b.getType() == Material.JUKEBOX, b -> {
                        if (!(b.getState(false) instanceof Jukebox jb) || jb.hasRecord()) return;
                        for (int i = 0; i < w.pockets.getSize(); i++) {
                            ItemStack it = w.pockets.getItem(i);
                            if (it == null || !isDisc(it.getType())) continue;
                            jb.setRecord(it.asOne());
                            it.subtract();
                            w.pockets.setItem(i, it.getAmount() > 0 ? it : null);
                            jb.startPlaying();
                            break;
                        }
                    });
                }
                case 2 -> {
                    // Listen a while, nodding along.
                    if (++listen > 600) return true;
                    if (listen % 30 == 0) w.anim.nod();
                    if (listen % 80 == 0) w.anim.look(box.getLocation().add(0.5, 1, 0.5));
                    return false;
                }
                default -> {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void stop() {
            if (step != null) step.stop();
            w.mover.stop();
        }

        @Override
        public String label() {
            if (phase == 2) return "Listening to music";
            return step != null ? step.label() : "Putting on a record";
        }

        @Override
        public boolean freeTime() {
            return true;
        }
    }
}
