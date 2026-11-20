package nl.spoonpaco.optimuslauncher;

/** Kleurthema's voor de cockpit en de toetsen. */
public final class Theme {
    public final String id, name, desc;
    public final int speed, accent, trip, accu, tank, north;
    public final int speedTint, speedEdge, accentTint, accentEdge;
    public final int rib, bgTop, key;

    private Theme(String id, String name, String desc, int speed, int accent, int trip, int accu, int tank, int north,
                  int speedTint, int speedEdge, int accentTint, int accentEdge, int rib, int bgTop, int key) {
        this.id = id; this.name = name; this.desc = desc;
        this.speed = speed; this.accent = accent; this.trip = trip; this.accu = accu; this.tank = tank; this.north = north;
        this.speedTint = speedTint; this.speedEdge = speedEdge; this.accentTint = accentTint; this.accentEdge = accentEdge;
        this.rib = rib; this.bgTop = bgTop; this.key = key;
    }

    /** Het origineel: rode snelheid, amber, groene rit, amber toetsen. */
    public static final Theme LED = new Theme("led", "LED Cockpit", "Rood, amber en groen LED, zoals de schets",
            0xFFFF2D23, 0xFFFFB21A, 0xFF3BE07A, 0xFF2F8CFF, 0xFFFFD23A, 0xFFFF2D23,
            0xFF2B0505, 0xFF4A0D0D, 0xFF241600, 0xFF4A3000, 0x660E0E12, 0xFF17171D, 0xFFFF8A1F);

    /** Vacuum-fluorescent display van digitale dashboards uit de jaren 80: turquoise met koel blauw. */
    public static final Theme VFD = new Theme("vfd", "VFD Dash", "Turquoise fluorescerend, zoals digitale 80s-dashboards",
            0xFF3FF5D2, 0xFF3FF5D2, 0xFF7CFFB0, 0xFF5AB8FF, 0xFF9BE8FF, 0xFFFF5A4A,
            0xFF032420, 0xFF0B4A40, 0xFF032420, 0xFF0B4A40, 0x66081614, 0xFF0C1A1A, 0xFF3FF5D2);

    /** Neon-raster: magenta en cyaan op diep paars. */
    public static final Theme NEON = new Theme("neon", "Neon Grid", "Magenta en cyaan neon op diep paars",
            0xFFFF2BD6, 0xFF22E4FF, 0xFF22E4FF, 0xFFB66BFF, 0xFFFFE45C, 0xFFFF2BD6,
            0xFF2A0628, 0xFF4A0D48, 0xFF06242A, 0xFF0B4650, 0x66140820, 0xFF1A0C24, 0xFF22E4FF);

    public static final Theme[] ALL = {LED, VFD, NEON};

    public static Theme byId(String id) {
        for (Theme t : ALL) if (t.id.equals(id)) return t;
        return LED;
    }
}
