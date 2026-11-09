package villagerworkers;

/** Something a worker is doing: farming a crop, taking a walk, chatting, sleeping... */
interface Activity {
    /** Called every tick. Returns true when finished. */
    boolean tick();

    /** Clean up (also called when interrupted). */
    default void stop() {}

    /** Shown in the worker's menu, e.g. "Taking a walk". */
    String label();

    /** Free time (chatting, walking, music...): stopped as soon as there's work to do. */
    default boolean freeTime() {
        return false;
    }

    /** Can another worker interrupt this to start a chat? */
    default boolean interruptible() {
        return false;
    }
}
