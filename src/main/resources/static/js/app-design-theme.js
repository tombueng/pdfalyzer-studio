/* === PDFalyzer Studio — Design Theme Picker ===
 * Adds a design-theme panel anchored to the status bar.
 * Applies data-design on <html>, persists to localStorage.
 *
 * Design themes control shape, font, shadow, and motion.
 * They are orthogonal to color themes.
 */
(function () {
    'use strict';

    const STORAGE_KEY = 'pdfalyzer-design';

    /* ── Design theme catalogue ───────────────────────────────────────
     * Each entry: { id, name, icon, desc, shapes }
     *   shapes: array of { w (px width), r (border-radius px) } for preview strip
     * ─────────────────────────────────────────────────────────────── */
    const DESIGNS = [
        {
            id:     'studio',
            name:   'Studio',
            icon:   'fa-layer-group',
            desc:   'Default — balanced & technical',
            shapes: [{ w: 28, r: 5 }, { w: 20, r: 4 }, { w: 36, r: 3 }]
        },
        {
            id:     'blade',
            name:   'Blade',
            icon:   'fa-terminal',
            desc:   'Brutalist — sharp, mono, dense',
            shapes: [{ w: 28, r: 0 }, { w: 20, r: 0 }, { w: 36, r: 0 }]
        },
        {
            id:     'breeze',
            name:   'Breeze',
            icon:   'fa-wind',
            desc:   'Fluent — rounded, soft, smooth',
            shapes: [{ w: 28, r: 14 }, { w: 20, r: 10 }, { w: 36, r: 18 }]
        },
        {
            id:     'ether',
            name:   'Ether',
            icon:   'fa-atom',
            desc:   'Glass — frosted, glowing, blur',
            shapes: [{ w: 28, r: 8 }, { w: 20, r: 6 }, { w: 36, r: 10 }]
        },
        {
            id:     'neon',
            name:   'Neon',
            icon:   'fa-lightbulb',
            desc:   'Sign — glowing borders, pulsing accent',
            shapes: [{ w: 28, r: 6 }, { w: 20, r: 4 }, { w: 36, r: 6 }]
        },
        {
            id:     'noir',
            name:   'Noir',
            icon:   'fa-film',
            desc:   'Noir — serif, diagonal cuts, drama',
            shapes: [{ w: 28, r: 0 }, { w: 20, r: 0 }, { w: 36, r: 0 }]
        },
        {
            id:     'chrome',
            name:   'Chrome',
            icon:   'fa-circle-half-stroke',
            desc:   'Metal — bevel sheen, reflective surfaces',
            shapes: [{ w: 28, r: 4 }, { w: 20, r: 3 }, { w: 36, r: 4 }]
        },
        {
            id:     'zen',
            name:   'Zen',
            icon:   'fa-circle-dot',
            desc:   'Minimal — invisible borders, micro-shadows',
            shapes: [{ w: 28, r: 6 }, { w: 20, r: 6 }, { w: 36, r: 6 }]
        },
        {
            id:     'retro',
            name:   'Retro',
            icon:   'fa-desktop',
            desc:   '90s — raised bevel, Windows 9x classic',
            shapes: [{ w: 28, r: 0 }, { w: 20, r: 0 }, { w: 36, r: 0 }]
        }
    ];

    /* ── Apply design ─────────────────────────────────────────────── */
    function applyDesign(id) {
        if (id === 'studio') {
            document.documentElement.removeAttribute('data-design');
        } else {
            document.documentElement.setAttribute('data-design', id);
        }
        localStorage.setItem(STORAGE_KEY, id);

        document.querySelectorAll('.design-option').forEach(el => {
            el.classList.toggle('active', el.dataset.designId === id);
        });

        const entry = DESIGNS.find(d => d.id === id);
        const lbl = document.querySelector('.design-status-label');
        if (lbl && entry) lbl.textContent = entry.name;
    }

    /* ── Build shape preview strip ────────────────────────────────── */
    function buildPreview(shapes) {
        const wrap = document.createElement('div');
        wrap.className = 'design-preview';
        shapes.forEach(s => {
            const el = document.createElement('span');
            el.className = 'dp-shape';
            el.style.width         = s.w + 'px';
            el.style.borderRadius  = s.r + 'px';
            wrap.appendChild(el);
        });
        return wrap;
    }

    /* ── Build picker panel ───────────────────────────────────────── */
    function buildPanel() {
        const saved = localStorage.getItem(STORAGE_KEY) || 'studio';
        const panel = document.createElement('div');
        panel.id = 'designPickerPanel';

        const hdr = document.createElement('div');
        hdr.className = 'design-panel-header';
        hdr.innerHTML =
            `<span><i class="fas fa-sliders me-2"></i>Design Theme</span>` +
            `<button type="button" class="design-panel-close" id="designPickerClose" title="Close"` +
            ` aria-label="Close"><i class="fas fa-times"></i></button>`;
        panel.appendChild(hdr);

        const list = document.createElement('div');
        list.className = 'design-options-list';

        DESIGNS.forEach(d => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'design-option' + (d.id === saved ? ' active' : '');
            btn.dataset.designId = d.id;
            btn.title = d.name;

            const iconWrap = document.createElement('div');
            iconWrap.className = 'design-option-icon';
            iconWrap.innerHTML = `<i class="fas ${d.icon}"></i>`;

            const info = document.createElement('div');
            info.className = 'design-option-info';
            info.innerHTML =
                `<span class="design-option-name">${d.name}</span>` +
                `<span class="design-option-desc">${d.desc}</span>`;
            info.appendChild(buildPreview(d.shapes));

            const check = document.createElement('span');
            check.className = 'design-option-check';
            check.innerHTML = `<i class="fas fa-check"></i>`;

            btn.appendChild(iconWrap);
            btn.appendChild(info);
            btn.appendChild(check);
            btn.addEventListener('click', () => { applyDesign(d.id); });
            list.appendChild(btn);
        });

        panel.appendChild(list);
        return panel;
    }

    /* ── Toggle panel ─────────────────────────────────────────────── */
    function togglePanel() {
        const panel = document.getElementById('designPickerPanel');
        if (!panel) return;
        const opening = !panel.classList.contains('open');
        if (opening) {
            const dt = document.getElementById('displayTuningPanel');
            const tp = document.getElementById('themePickerPanel');
            if (dt) dt.classList.remove('open');
            if (tp) tp.classList.remove('open');
        }
        panel.classList.toggle('open');
    }

    /* ── Close panel on outside click ────────────────────────────── */
    function onDocClick(e) {
        const panel = document.getElementById('designPickerPanel');
        const btn   = document.getElementById('designPickerStatusBtn');
        if (!panel || !panel.classList.contains('open')) return;
        if (panel.contains(e.target) || (btn && btn.contains(e.target))) return;
        panel.classList.remove('open');
    }

    /* ── Init ─────────────────────────────────────────────────────── */
    function init() {
        const saved = localStorage.getItem(STORAGE_KEY) || 'studio';
        applyDesign(saved);

        const panel = buildPanel();
        document.body.appendChild(panel);

        document.getElementById('designPickerClose')
            .addEventListener('click', () => panel.classList.remove('open'));

        const statusBtn = document.getElementById('designPickerStatusBtn');
        if (statusBtn) {
            statusBtn.addEventListener('click', togglePanel);
            const entry = DESIGNS.find(d => d.id === saved);
            const lbl = statusBtn.querySelector('.design-status-label');
            if (lbl && entry) lbl.textContent = entry.name;
        }

        document.addEventListener('click', onDocClick, true);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    /* Early-apply before render to avoid flash */
    (function earlyApply() {
        const id = localStorage.getItem(STORAGE_KEY);
        if (id && id !== 'studio') {
            document.documentElement.setAttribute('data-design', id);
        }
    })();
})();
