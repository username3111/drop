'use strict';
/* global gsap, ScrollTrigger */

// ═══════════════════════════════════════════════════════
//   VISUAL.JS — Aceternity + Magic UI + GSAP effects
// ═══════════════════════════════════════════════════════

// ── Mouse spotlight — Aceternity Spotlight ──────────────
const spotlight = document.getElementById('spotlight');
if (spotlight) {
  document.addEventListener('mousemove', e => {
    spotlight.style.left = e.clientX + 'px';
    spotlight.style.top  = e.clientY + 'px';
  });
  document.addEventListener('mouseleave', () => { spotlight.style.opacity = '0'; });
  document.addEventListener('mouseenter', () => { spotlight.style.opacity = '1'; });
}

// ── Card glow tracking — Aceternity card spotlight ──────
document.querySelectorAll('.glass-card').forEach(card => {
  card.addEventListener('mousemove', e => {
    const r = card.getBoundingClientRect();
    const x = ((e.clientX - r.left) / r.width  * 100).toFixed(1);
    const y = ((e.clientY - r.top)  / r.height * 100).toFixed(1);
    card.style.setProperty('--mx', x + '%');
    card.style.setProperty('--my', y + '%');
  });
});

// ── 3-D card tilt — Aceternity tilt effect ──────────────
document.querySelectorAll('[data-tilt]').forEach(card => {
  const MAX = 5;
  card.addEventListener('mousemove', e => {
    const r  = card.getBoundingClientRect();
    const dx = (e.clientX - (r.left + r.width  / 2)) / (r.width  / 2);
    const dy = (e.clientY - (r.top  + r.height / 2)) / (r.height / 2);
    card.style.transform =
      `perspective(900px) rotateY(${(dx * MAX).toFixed(2)}deg) rotateX(${(-dy * MAX).toFixed(2)}deg) scale(1.012)`;
  });
  card.addEventListener('mouseleave', () => {
    card.style.transform = 'perspective(900px) rotateX(0deg) rotateY(0deg) scale(1)';
  });
});

// ── Dropper icon mode toggle ─────────────────────────────
(function () {
  document.querySelectorAll('input[name="dropperIconMode"]').forEach(r => {
    r.addEventListener('change', () => {
      if (typeof updatePreviewIcon === 'function') updatePreviewIcon();
    });
  });
})();

// ══════════════════════════════════════════════════════════
//   GSAP ANIMATIONS
// ══════════════════════════════════════════════════════════
gsap.registerPlugin(ScrollTrigger);

// ── Topbar ─────────────────────────────────────────────
gsap.fromTo('.topbar',
  { opacity: 0, y: -30 },
  { opacity: 1, y: 0, duration: .55, ease: 'power2.out' }
);

// ── Hero compact entrance ───────────────────────────────
gsap.fromTo('.hero-status',
  { opacity: 0, y: -18 },
  { opacity: 1, y: 0, duration: .45, delay: .2, ease: 'power2.out' }
);
gsap.fromTo('#vBadge',
  { opacity: 0, scale: .7 },
  { opacity: 1, scale: 1, duration: .38, delay: .38, ease: 'back.out(1.8)' }
);

// ── Cards — ScrollTrigger blur-reveal (Magic UI blur) ──
gsap.utils.toArray('.glass-card').forEach((card, i) => {
  gsap.fromTo(card,
    { opacity: 0, y: 50, filter: 'blur(8px)' },
    {
      opacity: 1, y: 0, filter: 'blur(0px)',
      duration: .65, ease: 'power2.out',
      delay: (i % 2) * .08,
      scrollTrigger: {
        trigger: card,
        start: 'top 88%',
        toggleActions: 'play none none none',
      }
    }
  );
});

// ── Build button — spring pop ───────────────────────────
gsap.fromTo('.btn-build',
  { opacity: 0, scale: .82 },
  {
    opacity: 1, scale: 1,
    duration: .65, ease: 'back.out(1.6)',
    scrollTrigger: { trigger: '.build-zone', start: 'top 90%' }
  }
);

// ── Aurora orbs — subtle scroll parallax ───────────────
gsap.to('.orb-1', { y: -120, ease: 'none', scrollTrigger: { scrub: 1.2 } });
gsap.to('.orb-2', { y: 100,  ease: 'none', scrollTrigger: { scrub: 1.8 } });
gsap.to('.orb-3', { y: -70,  ease: 'none', scrollTrigger: { scrub: 2.4 } });

// ── Ripple on build button click ────────────────────────
(function () {
  const btn = document.getElementById('buildBtn');
  if (!btn) return;
  btn.addEventListener('mouseenter', () => {
    gsap.fromTo(btn,
      { boxShadow: '0 0 0 0 rgba(6,182,212,.6)' },
      { boxShadow: '0 0 0 18px rgba(6,182,212,0)', duration: .75, ease: 'power2.out' }
    );
  });
})();

// ── Tab switch bounce ───────────────────────────────────
document.querySelectorAll('#projectTabs .tab').forEach(tab => {
  tab.addEventListener('click', function () {
    gsap.fromTo(this, { scale: .9 }, { scale: 1, duration: .28, ease: 'back.out(2.2)' });
  });
});

// ── Progress card entrance ──────────────────────────────
window._animateProgressCard = function () {
  const card = document.getElementById('progressCard');
  if (!card) return;
  gsap.fromTo(card,
    { opacity: 0, y: 32, filter: 'blur(6px)' },
    { opacity: 1, y: 0, filter: 'blur(0px)', duration: .5, ease: 'power2.out' }
  );
};
