/* === PDFalyzer Studio — Theme Picker ===
 * Accordion panel anchored to the status bar.
 * Applies data-theme on <html>, persists to localStorage.
 */
(function () {
    'use strict';

    const STORAGE_KEY = 'pdfalyzer-theme';

    /* ── Theme catalogue ──────────────────────────────────────────────
     * Each entry: [id, label, bg, card, accent, ok]
     * bg/card/accent/ok are used only for the swatch preview strip.
     * ─────────────────────────────────────────────────────────────── */
    const GROUPS = [
        {
            label: 'Cyberpunk / Neon',
            icon: 'fa-bolt',
            themes: [
                ['void-blue',        'Void Blue',         '#1a1a2e','#0f3460','#00d4ff','#20c997'],
                ['neon-tokyo',       'Neon Tokyo',        '#0a0010','#1e0040','#ff00aa','#00ffaa'],
                ['acid-rain',        'Acid Rain',         '#050f05','#0a1e0a','#39ff14','#00ff88'],
                ['synthwave',        'Synthwave',         '#0a0520','#1f1060','#cc00ff','#00ffcc'],
                ['cyberpunk-gold',   'Cyberpunk Gold',    '#0a0800','#1e1800','#ffe000','#00ff9f'],
                ['matrix',           'Matrix',            '#000800','#001800','#00ff41','#00ffcc'],
                ['plasma',           'Plasma',            '#06000f','#140040','#7b00ff','#00ffc8'],
                ['outrun',           'Outrun',            '#08000f','#200030','#ff6ec7','#00ff88'],
                ['ultraviolet',      'Ultraviolet',       '#050010','#120040','#4400ff','#00ff80'],
                ['electra',          'Electra',           '#001414','#003035','#00ffe5','#00ff99'],
                ['hot-wire',         'Hot Wire',          '#0f0000','#2e0a00','#ff3300','#00ff88'],
                ['laser-lime',       'Laser Lime',        '#020f00','#082500','#ccff00','#00ffaa'],
                ['quantum-blue',     'Quantum Blue',      '#000a1e','#001844','#00aaff','#00ffcc'],
                ['ghost-signal',     'Ghost Signal',      '#060810','#14182e','#a8c8ff','#66ffcc'],
                ['neon-coral',       'Neon Coral',        '#100408','#2e1018','#ff6655','#00ffaa'],
                ['techno-cyan',      'Techno Cyan',       '#000e10','#002530','#00eeff','#00ff88'],
                ['danger-zone',      'Danger Zone',       '#0f0000','#300000','#ff0055','#00ff88'],
                ['hologram',         'Hologram',          '#010814','#041830','#88ddff','#44ffcc'],
                ['static-discharge', 'Static Discharge',  '#080808','#181818','#ffff00','#00ff88'],
                ['plasma-storm',     'Plasma Storm',      '#08001a','#1e0050','#ff44ff','#00ffcc'],
            ]
        },
        {
            label: 'Dark Elegant',
            icon: 'fa-gem',
            themes: [
                ['midnight-rose',  'Midnight Rose',    '#1a0a10','#3d1525','#ff7eb3','#2ed8a7'],
                ['obsidian',       'Obsidian',         '#0a0a08','#1e1e19','#ff8c42','#44cc88'],
                ['onyx',           'Onyx',             '#0c0c0c','#222222','#c0c0c0','#44cc88'],
                ['stealth',        'Stealth',          '#0a0e08','#1c2918','#c9a82c','#4ab848'],
                ['abyss',          'Abyss',            '#06080f','#141a30','#6070ff','#22cc99'],
                ['carbon',         'Carbon',           '#080808','#1c1c1c','#ff5500','#55bb44'],
                ['eclipse',        'Eclipse',          '#100800','#281800','#ffcc44','#44cc88'],
                ['nightshade',     'Nightshade',       '#0a0814','#1c1838','#aa88ff','#44ddaa'],
                ['dark-matter',    'Dark Matter',      '#060e0e','#0f2828','#00d9c0','#55dd99'],
                ['phantom',        'Phantom',          '#0a0808','#201818','#ff6666','#44cc88'],
                ['bordeaux',       'Bordeaux',         '#0f0308','#280a1a','#cc3366','#33aa66'],
                ['slate-blue',     'Slate Blue',       '#080c14','#1a2035','#7799cc','#44aa88'],
                ['tungsten',       'Tungsten',         '#080a08','#1a1e1a','#88aa66','#55cc88'],
                ['charcoal',       'Charcoal',         '#0e0e0e','#242424','#b0b0b0','#44bb77'],
                ['velvet-void',    'Velvet Void',      '#0a0614','#1c1435','#8844cc','#44cc99'],
                ['iron-forge',     'Iron Forge',       '#0a0c0e','#202428','#778899','#44aa77'],
                ['dusk-embers',    'Dusk Embers',      '#100808','#281818','#cc7744','#448855'],
                ['deep-burgundy',  'Deep Burgundy',    '#0c0408','#260c18','#aa3355','#338866'],
                ['graphite',       'Graphite',         '#080a0c','#1e2226','#6688aa','#448877'],
                ['onyx-gold',      'Onyx Gold',        '#080800','#1c1a00','#ccaa44','#448844'],
            ]
        },
        {
            label: 'Nature / Organic',
            icon: 'fa-leaf',
            themes: [
                ['forest',        'Forest',        '#050f08','#122418','#44cc44','#88dd44'],
                ['ocean-deep',    'Ocean Deep',    '#030d14','#0c2038','#00b4d8','#00d8aa'],
                ['volcanic',      'Volcanic',      '#120600','#2e1400','#ff6a28','#44cc88'],
                ['autumn',        'Autumn',        '#100800','#281800','#d2691e','#88aa44'],
                ['aurora',        'Aurora',        '#040c10','#0e2830','#00ffbb','#a0ff40'],
                ['tundra',        'Tundra',        '#080e14','#18262e','#88c8f8','#77ddaa'],
                ['jungle',        'Jungle',        '#050c04','#10200c','#7dc241','#41c268'],
                ['lagoon',        'Lagoon',        '#040e0e','#0e2424','#00e5cc','#44ee88'],
                ['amber-cave',    'Amber Cave',    '#0e0800','#241800','#e8a030','#66aa44'],
                ['storm',         'Storm',         '#060810','#141a2e','#4488dd','#44ccaa'],
                ['desert-bloom',  'Desert Bloom',  '#100c06','#2e2010','#e8aa44','#44aa55'],
                ['coral-reef',    'Coral Reef',    '#0c0800','#261814','#ff7744','#44cc77'],
                ['monsoon',       'Monsoon',       '#06080e','#141824','#6688bb','#44aa88'],
                ['redwood',       'Redwood',       '#0e0600','#281408','#aa6633','#448844'],
                ['wildfire',      'Wildfire',      '#0e0400','#2e1000','#ff8833','#44cc66'],
                ['ice-field',     'Ice Field',     '#06080e','#141c28','#88bbdd','#44ccaa'],
                ['savanna',       'Savanna',       '#0e0c06','#28240e','#ccaa44','#668833'],
                ['canyon',        'Canyon',        '#0e0800','#2a1810','#cc6633','#448855'],
                ['peatland',      'Peatland',      '#080c04','#18240c','#778844','#44aa55'],
                ['estuary',       'Estuary',       '#040c08','#0e2418','#44aa77','#33bb88'],
            ]
        },
        {
            label: 'Pastel / Light',
            icon: 'fa-sun',
            themes: [
                ['rose-quartz',    'Rose Quartz',     '#fdf0f2','#ffffff','#e91e8c','#00695c'],
                ['cotton-candy',   'Cotton Candy',    '#f8f0ff','#ffffff','#cc44ee','#00695c'],
                ['mint-dream',     'Mint Dream',      '#f0fcf8','#ffffff','#00876a','#00695c'],
                ['cloud-day',      'Cloud Day',       '#f0f4ff','#ffffff','#2255dd','#00695c'],
                ['lavender-fields','Lavender Fields', '#f4f0ff','#ffffff','#6633bb','#00695c'],
                ['peach-cream',    'Peach Cream',     '#fff4ee','#ffffff','#c04820','#00695c'],
                ['arctic',         'Arctic',          '#f0f8ff','#ffffff','#006699','#00695c'],
                ['parchment',      'Parchment',       '#fdf8f0','#ffffff','#7a4e2c','#1f5e1f'],
                ['sakura',         'Sakura',          '#fff0f4','#ffffff','#cc2266','#00695c'],
                ['fog',            'Fog',             '#f0f2f5','#ffffff','#2e6688','#00695c'],
                ['butter',         'Butter',          '#fffde0','#ffffff','#b07700','#1a5c1a'],
                ['powder-pink',    'Powder Pink',     '#fff0f4','#ffffff','#bb2255','#1a5c33'],
                ['seafoam',        'Seafoam',         '#eefaf6','#ffffff','#00775e','#1a5c1a'],
                ['lilac-dream',    'Lilac Dream',     '#f5f0ff','#ffffff','#5522aa','#1a5c33'],
                ['apricot',        'Apricot',         '#fff4ec','#ffffff','#bb4400','#1a5c1a'],
                ['morning-mist',   'Morning Mist',    '#eef0f4','#ffffff','#245588','#1a5c33'],
                ['vanilla',        'Vanilla',         '#fdf8ee','#ffffff','#885522','#1a5c1a'],
                ['pistachio',      'Pistachio',       '#eefaee','#ffffff','#286633','#1a5c1a'],
                ['cornflower',     'Cornflower',      '#eef2ff','#ffffff','#2244aa','#1a5c33'],
                ['almond',         'Almond',          '#fdf4e8','#ffffff','#7a481a','#1a5c1a'],
            ]
        },
        {
            label: 'Retro / Terminal',
            icon: 'fa-terminal',
            themes: [
                ['amber-terminal', 'Amber Terminal', '#0a0600','#1e1000','#ffb000','#aacc00'],
                ['phosphor-green', 'Phosphor Green', '#000600','#001000','#33ff33','#55ff55'],
                ['cga-blue',       'CGA Blue',       '#000088','#0000cc','#55ffff','#55ff55'],
                ['vhs-glitch',     'VHS Glitch',     '#080010','#180030','#ff00ff','#00ffaa'],
                ['solarized',      'Solarized Dark', '#002b36','#0d4c5e','#268bd2','#859900'],
                ['monokai',        'Monokai',        '#272822','#3e3d32','#f92672','#a6e22e'],
                ['dracula',        'Dracula',        '#282a36','#44475a','#bd93f9','#50fa7b'],
                ['nord',           'Nord',           '#2e3440','#434c5e','#88c0d0','#a3be8c'],
                ['gruvbox',        'Gruvbox',        '#282828','#504945','#fabd2f','#b8bb26'],
                ['commodore64',    'Commodore 64',   '#40318d','#6850b0','#7869c4','#44ff88'],
                ['apple-ii',       'Apple II',       '#000000','#002800','#33ff33','#00cc44'],
                ['gameboy',        'Game Boy',       '#0f380f','#8bac0f','#9bbc0f','#1a7a1a'],
                ['bbs',            'BBS Terminal',   '#000022','#00006e','#5555ff','#55ff55'],
                ['dev-console',    'Dev Console',    '#1e1e1e','#333333','#569cd6','#6a9955'],
                ['github-dark',    'GitHub Dark',    '#0d1117','#21262d','#58a6ff','#3fb950'],
                ['sublime',        'Sublime',        '#272822','#3e3e3c','#66d9e8','#a6e22e'],
                ['tokyo-night',    'Tokyo Night',    '#1a1b26','#24283b','#7aa2f7','#9ece6a'],
                ['one-dark',       'One Dark',       '#282c34','#2c313a','#61afef','#98c379'],
                ['catppuccin',     'Catppuccin',     '#1e1e2e','#313244','#cba6f7','#a6e3a1'],
                ['everforest',     'Everforest',     '#2d353b','#3d484d','#83c092','#a7c080'],
            ]
        }
    ];

    /* ── Apply theme ─────────────────────────────────────────────── */
    function applyTheme(id) {
        if (id === 'void-blue') {
            document.documentElement.removeAttribute('data-theme');
        } else {
            document.documentElement.setAttribute('data-theme', id);
        }
        localStorage.setItem(STORAGE_KEY, id);

        document.querySelectorAll('.theme-option').forEach(el => {
            el.classList.toggle('active', el.dataset.themeId === id);
        });

        const entry = GROUPS.flatMap(g => g.themes).find(t => t[0] === id);
        const dot = document.querySelector('.theme-status-dot');
        if (dot && entry) dot.style.background = entry[4];
    }

    /* ── Build picker panel ──────────────────────────────────────── */
    function buildPanel() {
        const saved = localStorage.getItem(STORAGE_KEY) || 'void-blue';
        const panel = document.createElement('div');
        panel.id = 'themePickerPanel';

        // Header row with title + close button
        const panelHdr = document.createElement('div');
        panelHdr.className = 'theme-panel-header';
        panelHdr.innerHTML =
            `<span><i class="fas fa-palette me-2"></i>Color Theme</span>` +
            `<button type="button" class="theme-panel-close" id="themePickerClose" title="Close"` +
            ` aria-label="Close"><i class="fas fa-times"></i></button>`;
        panel.appendChild(panelHdr);

        GROUPS.forEach((group, gi) => {
            const collapseId = `themeGroupCollapse${gi}`;
            const isFirst = gi === 0;

            const hdr = document.createElement('button');
            hdr.type = 'button';
            hdr.className = 'theme-group-btn';
            hdr.setAttribute('data-bs-toggle', 'collapse');
            hdr.setAttribute('data-bs-target', `#${collapseId}`);
            hdr.setAttribute('aria-expanded', isFirst ? 'true' : 'false');
            hdr.setAttribute('aria-controls', collapseId);
            hdr.innerHTML =
                `<span><i class="fas ${group.icon} me-2"></i>${group.label}</span>` +
                `<i class="fas fa-chevron-down"></i>`;
            panel.appendChild(hdr);

            const collapse = document.createElement('div');
            collapse.id = collapseId;
            collapse.className = 'collapse' + (isFirst ? ' show' : '');

            const grid = document.createElement('div');
            grid.className = 'theme-grid';

            group.themes.forEach(([id, label, bg, card, accent, ok]) => {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'theme-option' + (id === saved ? ' active' : '');
                btn.dataset.themeId = id;
                btn.title = label;
                btn.innerHTML =
                    `<span class="theme-swatch">` +
                        `<span class="swatch-bg"     style="background:${bg}"></span>` +
                        `<span class="swatch-card"   style="background:${card}"></span>` +
                        `<span class="swatch-accent" style="background:${accent}"></span>` +
                        `<span class="swatch-ok"     style="background:${ok}"></span>` +
                    `</span>` +
                    `<span class="theme-name">${label}</span>`;
                btn.addEventListener('click', () => { applyTheme(id); });
                grid.appendChild(btn);
            });

            collapse.appendChild(grid);
            panel.appendChild(collapse);
        });

        return panel;
    }

    /* ── Toggle panel ────────────────────────────────────────────── */
    function togglePanel() {
        const panel = document.getElementById('themePickerPanel');
        if (!panel) return;
        const opening = !panel.classList.contains('open');
        if (opening) {
            const dt = document.getElementById('displayTuningPanel');
            const dp = document.getElementById('designPickerPanel');
            if (dt) dt.classList.remove('open');
            if (dp) dp.classList.remove('open');
        }
        panel.classList.toggle('open');
    }

    /* ── Init ────────────────────────────────────────────────────── */
    function init() {
        const saved = localStorage.getItem(STORAGE_KEY) || 'void-blue';
        applyTheme(saved);

        // Inject panel into body
        const panel = buildPanel();
        document.body.appendChild(panel);

        // Wire close button inside panel
        document.getElementById('themePickerClose')
            .addEventListener('click', () => panel.classList.remove('open'));

        // Wire status-bar button
        const statusBtn = document.getElementById('themePickerStatusBtn');
        if (statusBtn) {
            statusBtn.addEventListener('click', togglePanel);
            const entry = GROUPS.flatMap(g => g.themes).find(t => t[0] === saved);
            const dot = statusBtn.querySelector('.theme-status-dot');
            if (dot && entry) dot.style.background = entry[4];
        }

        // Remove legacy navbar button if still present
        const legacyBtn = document.getElementById('themePickerBtn');
        if (legacyBtn) legacyBtn.closest('.dropdown')?.remove();

        // Close on outside click
        document.addEventListener('click', (e) => {
            if (!panel.classList.contains('open')) return;
            if (panel.contains(e.target) || (statusBtn && statusBtn.contains(e.target))) return;
            panel.classList.remove('open');
        }, true);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    // Early-apply before render
    (function earlyApply() {
        const id = localStorage.getItem(STORAGE_KEY);
        if (id && id !== 'void-blue') {
            document.documentElement.setAttribute('data-theme', id);
        }
    })();
})();
