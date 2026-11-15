import { afterNextRender, DestroyRef, Directive, DOCUMENT, effect, ElementRef, inject, input, signal } from '@angular/core';

type Theme = 'light' | 'pink' | 'dark';
type Particle = {
  x: number; y: number; size: number; speed: number; spin: number; angle: number;
  phase: number; kind: number; held: boolean;
};

const PALETTES = {
  light: ['#d292a7', '#daa468', '#aa9acc', '#8fa286'],
  pink: ['#d47c9f', '#e596b4', '#bf87ab', '#a1ae8e'],
} as const;
const MASK = '.site-header, .main-nav, .page-heading, .card, .info-strip, .preview-status:not(.visually-hidden), .preview > .section-heading, .help-links, .guide-back, .agenda-add, .connection:not(.visually-hidden), .new-care > form';
const STEP_MS = 1000 / 30;
const PLATFORM = 42;

@Directive({ selector: 'canvas[appFallingDecor]' })
export class FallingDecor {
  readonly appFallingDecor = input<Theme>('light');
  private readonly stage = signal<DecorStage | null>(null);

  constructor() {
    const canvas = inject(ElementRef<HTMLCanvasElement>).nativeElement;
    const doc = inject(DOCUMENT);
    const destroy = inject(DestroyRef);
    afterNextRender(() => {
      const view = doc.defaultView;
      if (!view || typeof Path2D === 'undefined') return;
      const ctx = canvas.getContext('2d', { alpha: true });
      if (!ctx) return;
      const stage = new DecorStage(canvas, ctx, doc, view);
      const media = view.matchMedia('(prefers-reduced-motion: reduce), (forced-colors: active)');
      const sync = () => stage.setMotion(!media.matches);
      sync();
      media.addEventListener('change', sync);
      this.stage.set(stage);
      destroy.onDestroy(() => {
        media.removeEventListener('change', sync);
        stage.stop();
      });
    });
    effect(() => this.stage()?.setTheme(this.appFallingDecor()));
  }
}

class DecorStage {
  private readonly star = new Path2D('M0-9 2.8-3.1 9.2-2.3 4.6 2.1 5.8 8.5 0 5.3-5.8 8.5-4.6 2.1-9.2-2.3-2.8-3.1Z');
  private readonly sparkle = new Path2D('M0-8C.9-3.2 3.2-.9 8 0 3.2.9.9 3.2 0 8-.9 3.2-3.2.9-8 0-3.2-.9-.9-3.2 0-8Z');
  private readonly planet = new Path2D('M7 0a7 7 0 1 1-14 0 7 7 0 1 1 14 0Z');
  private readonly backRing = new Path2D('M-11 0a11 3.1 0 1 1 22 0');
  private readonly frontRing = new Path2D('M-11 0a11 3.1 0 0 0 22 0');
  private readonly petal = new Path2D('M0 6C-7 3-8-2-5-5c2-2 4-1 5 1 1-2 4-3 6-1C9-2 6 3 0 6Z');
  private readonly leaf = new Path2D('M-6 5C-7-2-3-6 6-7 7 1 3 6-6 5ZM-6 5 2-3');
  private readonly lobe = new Path2D('M0 0C-5-1-5-7 0-8c5 1 5 7 0 8Z');
  private abort?: AbortController;
  private frame = 0;
  private last = 0;
  private clock = 0;
  private width = 0;
  private height = 0;
  private scale = 1;
  private dirty = true;
  private masks: DOMRect[] = [];
  private particles: Particle[] = [];
  private theme: Theme = 'light';
  private pointer?: { x: number; y: number; settled: number };

  constructor(private readonly canvas: HTMLCanvasElement, private readonly ctx: CanvasRenderingContext2D,
              private readonly doc: Document, private readonly view: Window) {}

  setTheme(theme: Theme): void {
    this.theme = theme;
  }

  setMotion(enabled: boolean): void {
    if (enabled) {
      if (!this.abort) this.start();
    } else {
      this.stop();
    }
  }

  private start(): void {
    const abort = this.abort = new AbortController();
    const passive = { passive: true, signal: abort.signal };
    const markDirty = () => { this.dirty = true; };
    this.view.addEventListener('resize', markDirty, passive);
    this.view.addEventListener('scroll', markDirty, passive);
    this.view.addEventListener('pointermove', this.move, passive);
    this.doc.addEventListener('pointerleave', this.release, passive);
    this.view.addEventListener('blur', this.release, passive);
    this.doc.addEventListener('visibilitychange', this.visibility, passive);
    const main = this.doc.getElementById('conteudo');
    const resize = new ResizeObserver(markDirty);
    const mutation = new MutationObserver(markDirty);
    if (main) {
      resize.observe(main);
      mutation.observe(main, { childList: true, subtree: true });
    }
    abort.signal.addEventListener('abort', () => { resize.disconnect(); mutation.disconnect(); }, { once: true });
    this.dirty = true;
    this.visibility();
  }

  stop(): void {
    this.abort?.abort();
    this.abort = undefined;
    this.view.cancelAnimationFrame(this.frame);
    this.frame = 0;
    this.last = 0;
    this.release();
    this.ctx.clearRect(0, 0, this.width, this.height);
  }

  private readonly visibility = () => {
    this.view.cancelAnimationFrame(this.frame);
    this.last = 0;
    this.release();
    this.frame = this.doc.hidden ? 0 : this.view.requestAnimationFrame(this.draw);
  };

  private readonly release = () => {
    this.pointer = undefined;
    for (const particle of this.particles) particle.held = false;
  };

  private readonly move = (event: PointerEvent) => {
    if (event.pointerType !== 'mouse') return;
    const x = event.clientX;
    const y = event.clientY - 9;
    const old = this.pointer;
    if (!old || Math.hypot(x - old.x, y - old.y) >= 8) {
      this.release();
      this.pointer = { x, y, settled: this.clock + 0.12 };
    }
  };

  private layout(): void {
    this.dirty = false;
    const width = this.canvas.clientWidth;
    const height = this.canvas.clientHeight;
    if (width !== this.width || height !== this.height) {
      this.width = width;
      this.height = height;
      this.scale = Math.min(this.view.devicePixelRatio || 1, 1.25, 2100 / (width || 1), 1500 / (height || 1));
      this.canvas.width = Math.round(width * this.scale);
      this.canvas.height = Math.round(height * this.scale);
      this.ctx.setTransform(this.scale, 0, 0, this.scale, 0, 0);
      const count = Math.max(7, Math.min(38, Math.round(width * height / 45000)));
      this.particles = Array.from({ length: count }, () => this.newParticle(Math.random() * height));
    }
    this.masks = Array.from(this.doc.querySelectorAll(MASK), node => node.getBoundingClientRect())
      .filter(r => r.width && r.height && r.bottom > 0 && r.top < height);
  }

  private newParticle(y: number): Particle {
    const random = Math.random;
    return { x: random() * this.width, y, size: 10 + random() * 9, speed: 24 + random() * 23,
      spin: (random() - .5) * 1.4, angle: random() * 6.28, phase: random() * 6.28,
      kind: Math.floor(random() * 4), held: false };
  }

  private readonly draw = (now: number) => {
    this.frame = this.view.requestAnimationFrame(this.draw);
    const elapsed = this.last ? now - this.last : STEP_MS;
    if (elapsed < STEP_MS - 1) return;
    this.last = now;
    const dt = Math.min(elapsed / 1000, .055);
    this.clock += dt;
    if (this.dirty) this.layout();
    this.ctx.clearRect(0, 0, this.width, this.height);
    const pointer = this.pointer;
    for (let i = 0; i < this.particles.length; i++) {
      const f = this.particles[i];
      if (!f.held) {
        f.x += Math.sin(this.clock * 1.6 + f.phase) * 19 * dt;
        f.angle += f.spin * dt;
        const bottom = f.y + f.size * .48;
        const next = bottom + f.speed * dt;
        if (pointer && this.clock >= pointer.settled && Math.abs(f.x - pointer.x) <= PLATFORM
            && bottom <= pointer.y && next >= pointer.y) {
          f.held = true;
          f.y = pointer.y - f.size * .48;
        } else {
          f.y += f.speed * dt;
        }
      }
      if (f.y > this.height + 15 || f.x < -20 || f.x > this.width + 20) {
        this.particles[i] = this.newParticle(-15 - Math.random() * 70);
        continue;
      }
      const radius = f.size * .7 + 4;
      if (this.masks.some(r => f.x + radius > r.left && f.x - radius < r.right
                           && f.y + radius > r.top && f.y - radius < r.bottom)) continue;
      this.paint(f);
    }
  };

  private paint(f: Particle): void {
    const ctx = this.ctx;
    ctx.save();
    ctx.translate(f.x, f.y);
    ctx.rotate(f.angle);
    ctx.scale(f.size / 18, f.size / 18);
    if (this.theme === 'dark') {
      this.paintSky(f.kind);
    } else {
      this.paintFlower(f.kind, this.theme);
    }
    ctx.restore();
  }

  private paintFlower(kind: number, theme: Exclude<Theme, 'dark'>): void {
    const ctx = this.ctx;
    ctx.fillStyle = PALETTES[theme][kind];
    ctx.globalAlpha = .67;
    if (kind === 0 || kind === 2) {
      for (let i = 0; i < 5; i++) {
        ctx.fill(this.lobe);
        ctx.rotate(Math.PI * .4);
      }
      ctx.beginPath();
      ctx.fillStyle = '#a97855';
      ctx.arc(0, 0, 2.1, 0, Math.PI * 2);
      ctx.fill();
    } else {
      ctx.fill(kind === 1 ? this.petal : this.leaf);
      ctx.strokeStyle = ctx.fillStyle;
      ctx.lineWidth = .65;
      ctx.stroke(kind === 1 ? this.petal : this.leaf);
    }
  }

  private paintSky(kind: number): void {
    const ctx = this.ctx;
    ctx.globalAlpha = .78;
    if (kind < 2) {
      ctx.fillStyle = kind === 0 ? '#ffda9a' : '#e7d7ff';
      ctx.fill(kind === 0 ? this.star : this.sparkle);
      return;
    }

    ctx.rotate(-.38);
    ctx.strokeStyle = kind === 2 ? '#f7caad' : '#ccdcfa';
    ctx.lineWidth = 1.25;
    ctx.lineCap = 'round';
    ctx.stroke(this.backRing);
    ctx.fillStyle = kind === 2 ? '#c899df' : '#91afd8';
    ctx.fill(this.planet);
    ctx.beginPath();
    ctx.fillStyle = kind === 2 ? '#e6bfef' : '#c0d5f4';
    ctx.arc(-2.2, -2.6, 1.35, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke(this.frontRing);
  }
}
