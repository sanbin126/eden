/* 2D 横屏平台跳跃 · 第一阶段 + 第二阶段（小怪与踩踏）
 * 逻辑分辨率 960×540，固定步长物理，AABB 分轴碰撞
 */
(() => {
  'use strict';

  /* ============================================================
   * 1. 常量
   * ============================================================ */

  const VIEW_W = 960;
  const VIEW_H = 540;
  const STEP = 1 / 60;
  const COIN_R = 10;

  // 纵向摄像机：低位跟随锚点（玩家中心对齐屏幕 86% 高度），太阳纵向视差系数
  const CAM_ANCHOR_Y = 0.86;
  const SUN_PARALLAX_Y = 0.1;

  const MAX_HP = 3;
  const ENEMY_W = 32;
  const ENEMY_H = 32;
  const ENEMY_SPEED = 70;
  const STOMP_BOUNCE = -520;
  const STOMP_TOLERANCE = 12;
  const INVULN_TIME = 1.2;
  const HIT_KNOCKBACK_X = 260;
  const HIT_KNOCKBACK_Y = -300;

  // 二段跳：落地重置为 2 次；二段跳圆环反馈的存活时长
  const MAX_JUMPS = 2;
  const JUMP_RING_LIFE = 0.32;

  // 突进：持续 0.18s，结束后冷却 0.5s（总锁定期 0.68s）
  const DASH_TIME = 0.18;
  const DASH_CD = 0.5;
  const DASH_SPEED = 620;
  const DASH_GHOST_INTERVAL = 0.03;  // 每隔约 2 帧记录一个残影
  const DASH_GHOST_LIFE = 0.22;      // 残影淡出时长
  const DASH_CD_ALPHA = 0.55;        // 冷却期间角色不透明度

  const PHYS = {
    gravityUp: 1700,      // 上升阶段重力（略小 → 跳跃更飘逸）
    gravityDown: 2500,    // 下落阶段重力（略大 → 落地更干脆）
    maxFall: 900,         // 终端速度，防穿透
    moveAccel: 2400,
    airAccel: 1600,
    maxSpeed: 330,
    groundFriction: 2600,
    airDrag: 400,
    jumpVelocity: -720,
    jumpCut: 0.45,        // 提前松键时的速度削减
    coyoteTime: 0.10,     // 土狼时间
    jumpBuffer: 0.12      // 跳跃缓冲
  };

  // 角色精灵：只影响外观，碰撞箱仍是 player.w × player.h
  // 开关：改成 false 可强制显示占位图形，便于排查图片加载问题
  const USE_SPRITE_ART = true;
  const SPRITE_DIR = 'pixel/';
  const SPRITE_W = 112;         // 精灵画布宽（逻辑 28 的 4 倍）
  const SPRITE_H = 160;         // 精灵画布高（逻辑 40 的 4 倍）
  const SPRITE_FEET_Y = 151;    // 画布内脚底所在像素行，其余为透明留白
  const SPRITE_CENTER_X = 56;   // 画布水平中心
  const SPRITE_FRAMES = {
    idle: ['idle-1.png', 'idle-2.png'],
    run: ['run-1.png', 'run-2.png', 'run-3.png', 'run-4.png'],
    jump: ['jump.png'],
    fall: ['fall.png']
  };
  const SPRITE_FPS = { idle: 2.2, run: 10 };

  // 冬季环境美术（Kenney platformer-art-winter）：只影响平台与背景绘制
  const SNOW_DIR = 'snow/';
  const SNOW_TILE = 70;       // 素材原始尺寸 70×70
  const SNOW_TILES = ['tundraLeft', 'tundraMid', 'tundraRight', 'pineSapling', 'rock', 'snowHill', 'igloo'];
  // 静态装饰：groundY 为所在平台顶面，贴图底边对齐，不参与碰撞
  const SNOW_DECOR = [
    { tile: 'rock', x: 230, groundY: 480 },
    { tile: 'pineSapling', x: 540, groundY: 480 },
    { tile: 'pineSapling', x: 770, groundY: 480 },
    { tile: 'snowHill', x: 1160, groundY: 480 },
    { tile: 'pineSapling', x: 1410, groundY: 480 },
    { tile: 'rock', x: 1810, groundY: 480 },
    { tile: 'pineSapling', x: 2520, groundY: 480 },
    { tile: 'igloo', x: 2740, groundY: 480 }
  ];

  // 金币与小怪美术（Kenney new-platformer-pack）：只影响绘制，碰撞箱与拾取判定不变
  const ART_DIR = 'art/';
  const COIN_ART = 34;            // 金币贴图绘制边长（alpha 可见部分≈20，与原直径对齐）
  const ENEMY_ART = 40;           // 史莱姆贴图绘制边长（碰撞箱仍为 32×32）
  const ENEMY_WALK_FPS = 6;       // 两帧走路循环速度
  const ART_LIST = ['coin_gold'];
  const ENEMY_FRAMES = ['slime_normal_walk_a', 'slime_normal_walk_b'];

  // 渲染质感常量：纯绘制参数，不参与任何玩法 / 碰撞计算
  const SHADOW_COLOR = 'rgba(60, 100, 130, 0.3)';   // 贴地物体统一的深蓝扁椭圆阴影
  const CLOUD_PARALLAX = 0.12;                      // 云层横向视差系数（比雪丘更远）
  const CLOUDS = [
    { x: 90, y: 72, scale: 1, speed: 5, alpha: 0.34 },
    { x: 360, y: 128, scale: 0.72, speed: 8, alpha: 0.26 },
    { x: 640, y: 62, scale: 1.18, speed: 4, alpha: 0.3 },
    { x: 900, y: 150, scale: 0.86, speed: 7, alpha: 0.22 },
    { x: 1180, y: 96, scale: 1.05, speed: 6, alpha: 0.28 }
  ];
  // 远云：更慢、更淡、偏冷，只用来填满天空上缘
  const CLOUDS_FAR = [
    { x: 210, y: 96, scale: 1.35, speed: 2.5, alpha: 0.16 },
    { x: 720, y: 150, scale: 1.1, speed: 3.2, alpha: 0.13 },
    { x: 1240, y: 78, scale: 1.5, speed: 2, alpha: 0.15 }
  ];

  // —— 分层背景：数字为横向视差系数，明度由远到近逐层拉开 ——
  const RIDGE_FAR = { parallax: 0.10, baseY: 344, height: 128, color: '#b3d2e4', phase: 0 };
  const RIDGE_MID = { parallax: 0.20, baseY: 368, height: 96, color: '#8fb2c9', phase: 900 };
  const FOREST_NEAR = { parallax: 0.32, baseY: 396, height: 74, color: '#6f93af', phase: 2100 };
  const SUN_X = 790;
  const SUN_Y = 110;
  // 体积光：以太阳为顶点的斜向光柱，alpha 极低，只做气氛
  const LIGHT_BEAMS = [
    { dx: -60, halfW: 150, alpha: 0.05 },
    { dx: -220, halfW: 110, alpha: 0.04 },
    { dx: 110, halfW: 90, alpha: 0.035 }
  ];

  // 前景飘雪：三层景深，近层更大更实；位置由固定种子生成，逐帧只随时间下落
  const SNOWFALL = [
    { count: 30, parallax: 0.55, radius: 1.1, alpha: 0.32, fall: 26, drift: 10 },
    { count: 40, parallax: 0.90, radius: 1.7, alpha: 0.46, fall: 44, drift: 16 },
    { count: 18, parallax: 1.30, radius: 2.8, alpha: 0.58, fall: 64, drift: 26 }
  ];
  const SNOWFALL_SEEDS = SNOWFALL.map((layer, li) => {
    const seeds = [];
    let seed = 1337 + li * 977;
    for (let i = 0; i < layer.count; i++) {
      seed = (seed * 1103515245 + 12345) & 0x7fffffff;
      const nx = (seed % 10000) / 10000;
      seed = (seed * 1103515245 + 12345) & 0x7fffffff;
      seeds.push({ nx, ny: (seed % 10000) / 10000 });
    }
    return seeds;
  });

  // 落地扬雪：下落速度超过阈值才扬尘，避免走下缓坡时一路冒烟
  const LAND_DUST_MIN_VY = 320;
  const FX_DIR = 'art/fx/';
  const FX_LIST = ['smoke_05'];

  // 关卡数据：LEVELS[0] 为第 1 关，内容与拆分前完全一致
  // platforms 为静态平台；lifts 为升降台（move 定义正弦往返）；
  // checkpoints[0] 为本关出生点；coins 为 [x, y]；enemies 为初始位置
  const LEVELS = [
    {
      width: 3000,
      height: 1200,
      checkpoints: [{ x: 90, y: 480 - 40 }],

      // 地面平台（y = 480，厚度 60 铺到画面底部）
      // 浮空平台间隙按"可跳过"设计：最大跳跃高度约 150px、水平跨度约 250px
      platforms: [
        { x: 0, y: 480, w: 620, h: 60, ground: true },
        { x: 740, y: 480, w: 510, h: 60, ground: true },
        { x: 1380, y: 480, w: 520, h: 60, ground: true },
        { x: 2020, y: 480, w: 580, h: 60, ground: true },
        { x: 2720, y: 480, w: 280, h: 60, ground: true },

        { x: 300, y: 380, w: 110, h: 20 },
        { x: 470, y: 300, w: 100, h: 20 },
        { x: 660, y: 400, w: 120, h: 20 },
        { x: 830, y: 340, w: 110, h: 20 },
        { x: 1000, y: 270, w: 100, h: 20 },
        { x: 1130, y: 390, w: 150, h: 20 },
        { x: 1420, y: 360, w: 120, h: 20 },
        { x: 1600, y: 280, w: 110, h: 20 },
        { x: 1780, y: 350, w: 130, h: 20 },
        { x: 1960, y: 300, w: 120, h: 20 },
        { x: 2160, y: 380, w: 140, h: 20 },
        { x: 2380, y: 290, w: 120, h: 20 },
        { x: 2620, y: 360, w: 130, h: 20 }
      ],

      // 升降台（测试用 2 个）：分别放在两处地面缺口内，move.range 为往返总行程（±range/2），
      // speed 为角速度，phase 为初相
      lifts: [
        { x: 1295, y: 460, w: 80, h: 18, move: { axis: 'y', range: 80, speed: 0.9, phase: 1.6 } },
        { x: 1915, y: 460, w: 90, h: 18, move: { axis: 'y', range: 80, speed: 1.1, phase: 0 } }
      ],

      coins: [
        [340, 330], [505, 250], [700, 345], [875, 285],
        [1040, 215], [1200, 335], [1470, 305], [1645, 225],
        [1840, 295], [2010, 245], [2430, 235], [2820, 450]
      ],

      // 小怪：一段地面一只，初始朝向交替
      enemies: [
        { x: 420, y: 480 - 32 },
        { x: 950, y: 480 - 32 },
        { x: 1650, y: 480 - 32 },
        { x: 2350, y: 480 - 32 }
      ],

      flag: { x: 2890, y: 380, w: 46, h: 100 }
    }
  ];

  /* ============================================================
   * 2. 工具
   * ============================================================ */

  const clamp = (v, min, max) => (v < min ? min : v > max ? max : v);

  function roundRectPath(c, x, y, w, h, r) {
    const rr = Math.min(r, w / 2, h / 2);
    c.beginPath();
    c.moveTo(x + rr, y);
    c.lineTo(x + w - rr, y);
    c.quadraticCurveTo(x + w, y, x + w, y + rr);
    c.lineTo(x + w, y + h - rr);
    c.quadraticCurveTo(x + w, y + h, x + w - rr, y + h);
    c.lineTo(x + rr, y + h);
    c.quadraticCurveTo(x, y + h, x, y + h - rr);
    c.lineTo(x, y + rr);
    c.quadraticCurveTo(x, y, x + rr, y);
    c.closePath();
  }

  function overlaps(a, b) {
    return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
  }

  function circleHitsRect(cx, cy, r, rect) {
    const nx = clamp(cx, rect.x, rect.x + rect.w);
    const ny = clamp(cy, rect.y, rect.y + rect.h);
    const dx = cx - nx;
    const dy = cy - ny;
    return dx * dx + dy * dy <= r * r;
  }

  /* ============================================================
   * 3. 关卡与运行时状态
   * ============================================================ */

  // 运行时关卡：由 buildLevel 按索引从 LEVELS 重建，绘制与碰撞都只读这里
  const LEVEL = {
    index: 0,
    width: 0,
    height: 0,
    checkpoints: [],
    platforms: [],
    coins: [],
    enemies: [],
    flag: null
  };

  function buildLevel(index) {
    const i = clamp(Math.floor(Number(index)) || 0, 0, LEVELS.length - 1);
    const data = LEVELS[i];

    LEVEL.index = i;
    LEVEL.width = data.width;
    LEVEL.height = data.height;
    LEVEL.checkpoints = data.checkpoints.map((c) => ({ x: c.x, y: c.y }));
    LEVEL.coins = data.coins.map(([x, y], n) => ({ x, y, phase: n * 0.73, collected: false }));
    LEVEL.enemies = data.enemies.map((e, n) => ({
      x: e.x,
      y: e.y,
      w: ENEMY_W,
      h: ENEMY_H,
      dir: n % 2 === 0 ? 1 : -1,
      alive: true,
      phase: n * 0.9,
      spawnX: e.x,
      spawnY: e.y,
      spawnDir: n % 2 === 0 ? 1 : -1
    }));
    LEVEL.flag = { ...data.flag };

    // 移动平台：baseX/baseY 是关卡数据里的基准位置，运行时 x/y 表示当前实际位置。
    // 每次加载都重新克隆，避免重复加载后基准位置漂移
    LEVEL.platforms = data.platforms.concat(
      data.lifts.map((lift) => ({
        ...lift,
        move: { ...lift.move },
        baseX: lift.x,
        baseY: lift.y,
        prevX: lift.x,
        prevY: lift.y
      }))
    );
  }

  buildLevel(0);

  const game = {
    state: 'playing',       // playing | cleared
    coins: 0,
    totalCoins: LEVEL.coins.length,
    hp: MAX_HP,
    time: 0,
    camX: 0,
    camY: 0,
    particles: []
  };

  const player = {
    x: LEVEL.checkpoints[0].x,
    y: LEVEL.checkpoints[0].y,
    prevY: LEVEL.checkpoints[0].y,
    w: 28,
    h: 40,
    vx: 0,
    vy: 0,
    onGround: false,
    facing: 1,
    coyote: 0,
    jumpBuffer: 0,
    jumpCut: false,
    jumpsLeft: MAX_JUMPS,
    jumpRings: [],
    dashTime: 0,
    dashCd: 0,
    dashDir: 1,
    dashGhosts: [],
    dashGhostTimer: 0,
    respawnTimer: 0,
    invuln: 0
  };

  // 按索引加载关卡：重建运行时对象，并把玩家放回本关出生点
  function loadLevel(index) {
    buildLevel(index);
    game.totalCoins = LEVEL.coins.length;
    game.coins = 0;
    game.hp = MAX_HP;
    game.particles.length = 0;
    game.state = 'playing';
    respawn();
    player.respawnTimer = 0;
    updateHud();
  }

  /* ============================================================
   * 4. 输入
   * ============================================================ */

  const input = { left: false, right: false, jump: false, jumpPressed: false, dashPressed: false };

  function pressJump() {
    input.jump = true;
    input.jumpPressed = true;
  }

  function pressDash() {
    input.dashPressed = true;
  }

  window.addEventListener('keydown', (e) => {
    switch (e.code) {
      case 'ArrowLeft':
      case 'KeyA':
        input.left = true;
        e.preventDefault();
        break;
      case 'ArrowRight':
      case 'KeyD':
        input.right = true;
        e.preventDefault();
        break;
      case 'ArrowUp':
      case 'KeyW':
      case 'Space':
        e.preventDefault();
        if (!e.repeat) pressJump();
        else input.jump = true;
        break;
      case 'KeyK':
        e.preventDefault();
        if (!e.repeat) pressDash();
        break;
      case 'KeyR':
        if (!e.repeat) restart();
        break;
      default:
        break;
    }
  });

  window.addEventListener('keyup', (e) => {
    switch (e.code) {
      case 'ArrowLeft':
      case 'KeyA':
        input.left = false;
        break;
      case 'ArrowRight':
      case 'KeyD':
        input.right = false;
        break;
      case 'ArrowUp':
      case 'KeyW':
      case 'Space':
        input.jump = false;
        break;
      default:
        break;
    }
  });

  // 失焦时清空输入，避免"一直往前跑"
  window.addEventListener('blur', () => {
    input.left = false;
    input.right = false;
    input.jump = false;
  });

  document.addEventListener('contextmenu', (e) => e.preventDefault());

  function setupTouchControls() {
    const isTouch = window.matchMedia('(pointer: coarse)').matches || 'ontouchstart' in window;
    if (isTouch) document.body.classList.add('is-touch');

    document.querySelectorAll('.pad').forEach((btn) => {
      const action = btn.dataset.key;

      const release = () => {
        btn.classList.remove('is-active');
        if (action === 'jump') input.jump = false;
        else if (action in input) input[action] = false;
      };

      btn.addEventListener('pointerdown', (e) => {
        e.preventDefault();
        btn.setPointerCapture(e.pointerId);
        btn.classList.add('is-active');
        if (action === 'jump') pressJump();
        else if (action === 'dash') pressDash();
        else if (action === 'left' || action === 'right') input[action] = true;
      });

      btn.addEventListener('pointerup', release);
      btn.addEventListener('pointercancel', release);
      btn.addEventListener('lostpointercapture', release);
    });
  }

  /**
   * 左半屏浮动轮盘：手指落在屏幕左半边任意位置时，轮盘就地出现；
   * 只识别水平位移（超过死区才输出方向），抬手立即回中并隐藏。
   */
  function setupMoveWheel() {
    const stage = document.getElementById('stage');
    const wheel = document.getElementById('moveWheel');
    const knob = document.getElementById('moveKnob');
    if (!stage || !wheel || !knob) return;

    const DEAD_ZONE = 0.18; // 相对最大位移的死区，避免误触
    let pointerId = null;
    let originX = 0;
    let maxOffset = 0;

    function hide() {
      pointerId = null;
      wheel.hidden = true;
      wheel.classList.remove('is-active', 'is-left', 'is-right');
      knob.style.transform = 'translate(0px, 0px)';
      input.left = false;
      input.right = false;
    }

    function place(clientX, clientY) {
      const rect = stage.getBoundingClientRect();
      const radius = wheel.offsetWidth / 2;
      const x = clamp(clientX - rect.left, radius + 4, rect.width - radius - 4);
      const y = clamp(clientY - rect.top, radius + 4, rect.height - radius - 4);
      wheel.style.left = x + 'px';
      wheel.style.top = y + 'px';
    }

    stage.addEventListener('pointerdown', (e) => {
      if (pointerId !== null || e.pointerType === 'mouse') return;
      if (e.target.closest('.pad, .btn-restart, .clear, .portrait-tip')) return;

      const rect = stage.getBoundingClientRect();
      // 只有屏幕左半边能唤出轮盘，右半边留给跳跃等操作
      if (e.clientX - rect.left > rect.width / 2) return;

      pointerId = e.pointerId;
      originX = e.clientX;
      wheel.hidden = false;
      wheel.classList.add('is-active');
      place(e.clientX, e.clientY);
      maxOffset = wheel.offsetWidth * 0.26;
      knob.style.transform = 'translate(0px, 0px)';
      input.left = false;
      input.right = false;

      try {
        stage.setPointerCapture(e.pointerId);
      } catch (err) {
        /* 少数 WebView 不支持指针捕获，忽略即可 */
      }
      e.preventDefault();
    });

    stage.addEventListener('pointermove', (e) => {
      if (e.pointerId !== pointerId) return;
      const offset = clamp(e.clientX - originX, -maxOffset, maxOffset);
      knob.style.transform = `translate(${offset.toFixed(1)}px, 0px)`;

      const ratio = maxOffset > 0 ? offset / maxOffset : 0;
      input.left = ratio < -DEAD_ZONE;
      input.right = ratio > DEAD_ZONE;
      wheel.classList.toggle('is-left', input.left);
      wheel.classList.toggle('is-right', input.right);
      e.preventDefault();
    });

    stage.addEventListener('pointerup', (e) => {
      if (e.pointerId === pointerId) hide();
    });
    stage.addEventListener('pointercancel', (e) => {
      if (e.pointerId === pointerId) hide();
    });
    window.addEventListener('blur', hide);
  }

  /* ============================================================
   * 5. 画布 / 高清屏
   * ============================================================ */

  const canvas = document.getElementById('game');
  const ctx = canvas.getContext('2d');
  let dpr = 1;

  function resizeCanvas() {
    dpr = clamp(window.devicePixelRatio || 1, 1, 3);
    canvas.width = Math.round(VIEW_W * dpr);
    canvas.height = Math.round(VIEW_H * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.imageSmoothingEnabled = true;
  }

  window.addEventListener('resize', resizeCanvas);
  window.addEventListener('orientationchange', () => {
    setTimeout(resizeCanvas, 120);
  });

  /* ============================================================
   * 5.1 角色精灵资源（仅渲染层）
   * ============================================================ */

  const sprites = { idle: [], run: [], jump: [], fall: [] };
  let spriteFailed = false;   // 任意一帧失败 → 整体回退占位图形

  function loadSprites() {
    for (const state of Object.keys(SPRITE_FRAMES)) {
      for (const file of SPRITE_FRAMES[state]) {
        const img = new Image();
        img.onerror = () => {
          if (spriteFailed) return;
          spriteFailed = true;
          console.warn('[platformer] 角色精灵加载失败，已回退占位图形：' + img.src);
        };
        img.src = SPRITE_DIR + file;
        sprites[state].push(img);
      }
    }
  }

  /* ============================================================
   * 5.2 冬季环境素材（仅渲染层）
   * ============================================================ */

  const snowArt = {};

  function loadSnowArt() {
    for (const name of SNOW_TILES) {
      const img = new Image();
      img.onerror = () => {
        console.warn('[platformer] 冬季环境素材加载失败，已回退纯色平台：' + img.src);
      };
      img.src = SNOW_DIR + name + '.png';
      snowArt[name] = img;
    }
  }

  // 单张就绪判定：未加载完或 404 时返回 false，由调用方回退
  function snowReady(name) {
    const img = snowArt[name];
    return !!img && img.complete && img.naturalWidth > 0;
  }

  const coinArt = {};
  const enemyArt = {};
  const fxArt = {};

  function loadArt() {
    for (const name of ART_LIST) {
      const img = new Image();
      img.onerror = () => {
        console.warn('[platformer] 金币素材加载失败，已回退程序化绘制：' + img.src);
      };
      img.src = ART_DIR + name + '.png';
      coinArt[name] = img;
    }
    for (const name of ENEMY_FRAMES) {
      const img = new Image();
      img.onerror = () => {
        console.warn('[platformer] 小怪素材加载失败，已回退程序化绘制：' + img.src);
      };
      img.src = ART_DIR + 'enemies/' + name + '.png';
      enemyArt[name] = img;
    }
    for (const name of FX_LIST) {
      const img = new Image();
      img.onerror = () => {
        console.warn('[platformer] 粒子素材加载失败，已回退程序化绘制：' + img.src);
      };
      img.src = FX_DIR + name + '.png';
      fxArt[name] = img;
    }
  }

  // 单张就绪判定：未加载完或 404 时返回 false，由调用方回退
  function artReady(store, name) {
    const img = store[name];
    return !!img && img.complete && img.naturalWidth > 0;
  }

  /* ============================================================
   * 6. 玩家更新与碰撞
   * ============================================================ */

  // 升降台：按正弦在基准位置往返，位移直接写回 plat.x / plat.y，
  // 绘制、玩家碰撞、小怪探地都复用同一份当前坐标
  function updateMovingPlatforms() {
    for (const plat of LEVEL.platforms) {
      const move = plat.move;
      if (!move) continue;
      plat.prevX = plat.x;
      plat.prevY = plat.y;
      const offset = Math.sin(game.time * move.speed + (move.phase || 0)) * (move.range / 2);
      if (move.axis === 'x') plat.x = plat.baseX + offset;
      else plat.y = plat.baseY + offset;
    }
  }

  // 站在升降台上的玩家随平台位移：先按上一步的支撑关系搬动，
  // 再由 updatePlayer 的分轴碰撞把脚底对齐到平台当前表面
  function carryPlayerOnPlatforms() {
    const p = player;
    if (!p.onGround) return;
    for (const plat of LEVEL.platforms) {
      if (!plat.move) continue;
      const dx = plat.x - plat.prevX;
      const dy = plat.y - plat.prevY;
      if (dx === 0 && dy === 0) continue;
      const supported =
        p.x + p.w > plat.prevX && p.x < plat.prevX + plat.w &&
        Math.abs(p.y + p.h - plat.prevY) <= 1.5;
      if (!supported) continue;
      p.x += dx;
      p.y += dy;
    }
  }

  function updatePlayer(dt) {
    const p = player;
    p.prevY = p.y;

    // 突进计时：dashTime 剩余突进时间，dashCd 覆盖「突进中 + 冷却」
    if (p.dashCd > 0) p.dashCd = Math.max(0, p.dashCd - dt);
    if (p.dashTime > 0) p.dashTime = Math.max(0, p.dashTime - dt);

    const dir = (input.right ? 1 : 0) - (input.left ? 1 : 0);

    // 触发突进：方向取当前朝向，正按着方向键 / 摇杆时取输入方向
    if (input.dashPressed) {
      input.dashPressed = false;
      if (p.dashTime <= 0 && p.dashCd <= 0) {
        p.dashDir = dir !== 0 ? dir : p.facing;
        p.facing = p.dashDir;
        p.dashTime = DASH_TIME;
        p.dashCd = DASH_TIME + DASH_CD;
        p.dashGhostTimer = 0;
      }
    }
    const dashing = p.dashTime > 0;

    // 水平加速 / 惯性（突进期间锁定速度，不响应加减速）
    if (dashing) {
      p.vx = DASH_SPEED * p.dashDir;
    } else if (dir !== 0) {
      const accel = (p.onGround ? PHYS.moveAccel : PHYS.airAccel) * dt;
      p.vx = clamp(p.vx + dir * accel, -PHYS.maxSpeed, PHYS.maxSpeed);
      p.facing = dir;
    } else {
      const decel = (p.onGround ? PHYS.groundFriction : PHYS.airDrag) * dt;
      if (p.vx > 0) p.vx = Math.max(0, p.vx - decel);
      else if (p.vx < 0) p.vx = Math.min(0, p.vx + decel);
    }

    // 土狼时间
    p.coyote = p.onGround ? PHYS.coyoteTime : Math.max(0, p.coyote - dt);

    // 跳跃缓冲（消费一次按下沿）
    if (input.jumpPressed) {
      p.jumpBuffer = PHYS.jumpBuffer;
      input.jumpPressed = false;
    } else {
      p.jumpBuffer = Math.max(0, p.jumpBuffer - dt);
    }

    if (!dashing && p.jumpBuffer > 0 && (p.coyote > 0 || p.jumpsLeft > 0)) {
      const airJump = p.coyote <= 0;   // 土狼窗口之外 → 空中二段跳
      p.vy = PHYS.jumpVelocity;
      p.jumpBuffer = 0;
      p.coyote = 0;
      p.onGround = false;
      p.jumpCut = false;
      if (airJump) {
        p.jumpsLeft -= 1;
        // 视觉反馈：脚底生成一圈扩散圆环（纯代码绘制，不用图片）
        p.jumpRings.push({ x: p.x + p.w / 2, y: p.y + p.h, t: 0 });
      } else {
        p.jumpsLeft = MAX_JUMPS - 1;   // 地面起跳消耗第一次
      }
    }

    // 重力（上升 / 下落使用不同系数）；突进期间关闭重力
    if (dashing) {
      p.vy = 0;
    } else {
      const gravity = p.vy < 0 ? PHYS.gravityUp : PHYS.gravityDown;
      p.vy = Math.min(PHYS.maxFall, p.vy + gravity * dt);

      // 可变跳跃高度：松开跳跃键立刻削减上升速度
      if (!input.jump && p.vy < 0 && !p.jumpCut) {
        p.vy *= PHYS.jumpCut;
        p.jumpCut = true;
      }
    }

    // ---- 分轴碰撞：先 X 后 Y ----
    const wasOnGround = p.onGround;
    const fallSpeed = p.vy;      // 碰撞前的下落速度，用来判断落地是否需要扬雪
    p.onGround = false;

    p.x += p.vx * dt;
    for (const plat of LEVEL.platforms) {
      if (!overlaps(p, plat)) continue;
      if (p.vx > 0) p.x = plat.x - p.w;
      else if (p.vx < 0) p.x = plat.x + plat.w;
      p.vx = 0;
    }

    p.y += p.vy * dt;
    for (const plat of LEVEL.platforms) {
      if (!overlaps(p, plat)) continue;
      if (p.vy > 0) {
        p.y = plat.y - p.h;
        p.vy = 0;
        p.onGround = true;
        p.jumpCut = false;
        p.jumpsLeft = MAX_JUMPS;   // 落地重置可跳次数
      } else if (p.vy < 0) {
        p.y = plat.y + plat.h;
        p.vy = 0;
      }
    }

    // 落地扬雪：只在下落速度足够大时触发，走下缓坡不冒烟
    if (!wasOnGround && p.onGround && fallSpeed > LAND_DUST_MIN_VY) {
      spawnLandDust(p.x + p.w / 2, p.y + p.h);
    }

    // 关卡左右边界
    if (p.x < 0) {
      p.x = 0;
      p.vx = Math.max(0, p.vx);
    } else if (p.x + p.w > LEVEL.width) {
      p.x = LEVEL.width - p.w;
      p.vx = Math.min(0, p.vx);
    }

    // 突进期间重力关闭、vy 恒为 0，不会像普通落地那样压进平台，
    // 严格 AABB 判不到支撑面；这里补一次脚下探测，避免地面突进时丢失 onGround
    if (dashing) {
      p.onGround = platformAt(p.x + p.w / 2, p.y + p.h + 1);
    }

    // 掉出地图 → 回起点
    if (p.y > VIEW_H + 80) respawn();

    // 突进残影：按固定间隔采样位置（碰撞后的最终坐标），随后独立淡出
    if (dashing) {
      p.dashGhostTimer += dt;
      if (p.dashGhostTimer >= DASH_GHOST_INTERVAL) {
        p.dashGhostTimer -= DASH_GHOST_INTERVAL;
        p.dashGhosts.push({
          x: p.x,
          y: p.y,
          facing: p.facing,
          sprite: currentSprite(),
          t: 0
        });
      }
    }
    for (let i = p.dashGhosts.length - 1; i >= 0; i--) {
      p.dashGhosts[i].t += dt;
      if (p.dashGhosts[i].t >= DASH_GHOST_LIFE) p.dashGhosts.splice(i, 1);
    }

    // 二段跳圆环计时：扩散后淡出，超时移除
    for (let i = p.jumpRings.length - 1; i >= 0; i--) {
      p.jumpRings[i].t += dt;
      if (p.jumpRings[i].t >= JUMP_RING_LIFE) p.jumpRings.splice(i, 1);
    }

    if (p.respawnTimer > 0) p.respawnTimer = Math.max(0, p.respawnTimer - dt);
    if (p.invuln > 0) p.invuln = Math.max(0, p.invuln - dt);
  }

  function respawn() {
    const checkpoint = LEVEL.checkpoints[0];
    player.x = checkpoint.x;
    player.y = checkpoint.y;
    player.prevY = checkpoint.y;
    player.vx = 0;
    player.vy = 0;
    player.onGround = false;
    player.coyote = 0;
    player.jumpBuffer = 0;
    player.jumpCut = true;
    player.jumpsLeft = MAX_JUMPS;
    player.jumpRings.length = 0;
    player.dashTime = 0;
    player.dashCd = 0;
    player.dashDir = 1;
    player.dashGhosts.length = 0;
    player.dashGhostTimer = 0;
    player.respawnTimer = 0.5;
    player.invuln = 0;
    game.camX = clamp(player.x + player.w / 2 - VIEW_W * 0.4, 0, LEVEL.width - VIEW_W);
    game.camY = 0;
  }

  /* ============================================================
   * 7. 摄像机 / 金币 / 终点
   * ============================================================ */

  function updateCamera(dt) {
    const k = Math.min(1, dt * 8);

    const targetX = player.x + player.w / 2 - VIEW_W * 0.4;
    game.camX += (targetX - game.camX) * k;
    game.camX = clamp(game.camX, 0, LEVEL.width - VIEW_W);

    const targetY = player.y + player.h / 2 - VIEW_H * CAM_ANCHOR_Y;
    game.camY += (targetY - game.camY) * k;
    game.camY = clamp(game.camY, 0, Math.max(0, LEVEL.height - VIEW_H));
  }

  function updateCoins() {
    for (const coin of LEVEL.coins) {
      if (coin.collected) continue;
      if (circleHitsRect(coin.x, coin.y, COIN_R, player)) {
        coin.collected = true;
        game.coins += 1;
        spawnCoinBurst(coin.x, coin.y);
        updateHud();
      }
    }
  }

  function checkGoal() {
    if (overlaps(player, LEVEL.flag)) finish();
  }

  /* ============================================================
   * 7.5 小怪：巡逻 / 踩踏 / 受伤
   * ============================================================ */

  function platformAt(x, y) {
    for (const plat of LEVEL.platforms) {
      if (x >= plat.x && x <= plat.x + plat.w && y >= plat.y && y <= plat.y + plat.h) {
        return true;
      }
    }
    return false;
  }

  function updateEnemies(dt) {
    for (const e of LEVEL.enemies) {
      if (!e.alive) continue;

      const nextX = e.x + e.dir * ENEMY_SPEED * dt;

      // 探地：前脚点下方没有平台就反向，避免走下悬崖
      const footX = e.dir > 0 ? nextX + e.w + 2 : nextX - 2;
      const footY = e.y + e.h + 2;
      if (!platformAt(footX, footY)) {
        e.dir *= -1;
        continue;
      }

      // 撞墙：候选位置与平台相交就反向
      const probe = { x: nextX, y: e.y, w: e.w, h: e.h };
      let blocked = false;
      for (const plat of LEVEL.platforms) {
        if (overlaps(probe, plat)) {
          blocked = true;
          break;
        }
      }
      if (blocked) {
        e.dir *= -1;
        continue;
      }

      e.x = clamp(nextX, 0, LEVEL.width - e.w);
    }
  }

  function updateEnemyHits() {
    for (const e of LEVEL.enemies) {
      if (!e.alive || !overlaps(player, e)) continue;

      const prevBottom = player.prevY + player.h;
      const isStomp = player.vy > 0 && prevBottom <= e.y + STOMP_TOLERANCE;

      if (isStomp) {
        e.alive = false;
        player.vy = STOMP_BOUNCE;
        player.onGround = false;
        player.jumpCut = true;
        spawnEnemyBurst(e);
      } else if (player.invuln <= 0) {
        damagePlayer(e);
        if (game.state !== 'playing') return;   // 血量归零已整关重置
      }
    }
  }

  function damagePlayer(e) {
    game.hp -= 1;
    player.invuln = INVULN_TIME;

    const dir = player.x + player.w / 2 < e.x + e.w / 2 ? -1 : 1;
    player.vx = dir * HIT_KNOCKBACK_X;
    player.vy = HIT_KNOCKBACK_Y;
    player.onGround = false;
    player.jumpCut = true;

    updateHud();
    hudEl.classList.remove('is-hurt');
    void hudEl.offsetWidth;   // 强制回流，让抖动动画可以反复触发
    hudEl.classList.add('is-hurt');

    if (game.hp <= 0) restart();
  }

  function spawnEnemyBurst(e) {
    const cx = e.x + e.w / 2;
    const cy = e.y + e.h / 2;
    for (let i = 0; i < 8; i++) {
      const a = (Math.PI * 2 * i) / 8 + Math.random() * 0.35;
      const speed = 110 + Math.random() * 100;
      game.particles.push({
        x: cx,
        y: cy,
        vx: Math.cos(a) * speed,
        vy: Math.sin(a) * speed - 60,
        life: 0.4,
        max: 0.4,
        color: '#ff7b6b',
        square: true
      });
    }
  }

  function spawnCoinBurst(x, y) {
    for (let i = 0; i < 8; i++) {
      const a = (Math.PI * 2 * i) / 8 + Math.random() * 0.35;
      const speed = 90 + Math.random() * 90;
      game.particles.push({
        x,
        y,
        vx: Math.cos(a) * speed,
        vy: Math.sin(a) * speed - 40,
        life: 0.45,
        max: 0.45,
        color: '#ffe38a'
      });
    }
  }

  // 落地扬雪：三团极淡的雪雾，纯视觉，不参与碰撞
  function spawnLandDust(cx, cy) {
    for (let i = 0; i < 3; i++) {
      game.particles.push({
        x: cx + (i - 1) * 10,
        y: cy - 2,
        vx: (i - 1) * 46,
        vy: -18 - i * 5,
        life: 0.36,
        max: 0.36,
        color: '#f4fbff',
        dust: true
      });
    }
  }

  function updateParticles(dt) {
    const list = game.particles;
    for (let i = list.length - 1; i >= 0; i--) {
      const pt = list[i];
      pt.vy += (pt.dust ? 300 : 900) * dt;   // 雪雾很轻，下落比碎片慢
      pt.x += pt.vx * dt;
      pt.y += pt.vy * dt;
      pt.life -= dt;
      if (pt.life <= 0) list.splice(i, 1);
    }
  }

  /* ============================================================
   * 8. 状态切换
   * ============================================================ */

  const coinText = document.getElementById('coinText');
  const clearLayer = document.getElementById('clearLayer');
  const clearCoins = document.getElementById('clearCoins');
  const clearExtra = document.getElementById('clearExtra');
  const restartBtn = document.getElementById('restartBtn');
  const hpHearts = document.getElementById('hpHearts');
  const hudEl = document.querySelector('.hud');
  const hearts = hpHearts ? Array.from(hpHearts.querySelectorAll('.heart')) : [];

  hudEl.addEventListener('animationend', () => hudEl.classList.remove('is-hurt'));

  function updateHud() {
    coinText.textContent = `金币 ${game.coins} / ${game.totalCoins}`;
    hearts.forEach((el, i) => el.classList.toggle('is-empty', i >= game.hp));
  }

  function finish() {
    if (game.state !== 'playing') return;
    game.state = 'cleared';
    input.left = false;
    input.right = false;
    input.jump = false;

    clearCoins.textContent = String(game.coins);
    clearExtra.hidden = game.coins < game.totalCoins;
    clearLayer.hidden = false;
    restartBtn.blur();

    // 接入 App（WebView）时回传通关结果；浏览器里直接打开没有该桥，忽略即可
    try {
      const bridge = window.EdenGame;
      if (bridge && typeof bridge.onCleared === 'function') {
        bridge.onCleared(game.coins, game.totalCoins);
      }
    } catch (err) {
      /* 非 App 环境，忽略 */
    }
  }

  function restart() {
    const checkpoint = LEVEL.checkpoints[0];
    player.x = checkpoint.x;
    player.y = checkpoint.y;
    player.prevY = checkpoint.y;
    player.vx = 0;
    player.vy = 0;
    player.onGround = false;
    player.facing = 1;
    player.coyote = 0;
    player.jumpBuffer = 0;
    player.jumpCut = false;
    player.jumpsLeft = MAX_JUMPS;
    player.jumpRings.length = 0;
    player.dashTime = 0;
    player.dashCd = 0;
    player.dashDir = 1;
    player.dashGhosts.length = 0;
    player.dashGhostTimer = 0;
    player.respawnTimer = 0;
    player.invuln = 0;

    for (const coin of LEVEL.coins) coin.collected = false;
    for (const e of LEVEL.enemies) {
      e.x = e.spawnX;
      e.y = e.spawnY;
      e.dir = e.spawnDir;
      e.alive = true;
    }

    game.state = 'playing';
    game.coins = 0;
    game.hp = MAX_HP;
    game.particles.length = 0;
    game.camX = 0;
    game.camY = 0;

    input.left = false;
    input.right = false;
    input.jump = false;
    input.jumpPressed = false;
    input.dashPressed = false;

    clearLayer.hidden = true;
    updateHud();
  }

  restartBtn.addEventListener('click', restart);

  /* ============================================================
   * 9. 渲染
   * ============================================================ */

  const skyGradient = (() => {
    const g = ctx.createLinearGradient(0, 0, 0, VIEW_H);
    g.addColorStop(0, '#74aed4');
    g.addColorStop(0.55, '#b9dff3');
    g.addColorStop(1, '#e8f7ff');
    return g;
  })();

  // 暗角：一次性径向渐变，压住四角，去掉"网页截图"式的平铺感
  const vignetteGradient = (() => {
    const g = ctx.createRadialGradient(
      VIEW_W / 2, VIEW_H / 2, VIEW_H * 0.42,
      VIEW_W / 2, VIEW_H / 2, VIEW_H * 0.98
    );
    g.addColorStop(0, 'rgba(18, 42, 64, 0)');
    g.addColorStop(1, 'rgba(18, 42, 64, 0.26)');
    return g;
  })();

  function drawBackground() {
    ctx.fillStyle = skyGradient;
    ctx.fillRect(0, 0, VIEW_W, VIEW_H);

    drawClouds(CLOUDS_FAR, 0.06, '#eaf6ff');   // 远云：低对比，几乎融进天空
    drawSun();

    // 远山 → 雾带 → 中景山脊 → 雪丘 → 雾带 → 针叶林剪影：靠明度台阶拉出纵深
    drawHills(RIDGE_FAR.parallax, RIDGE_FAR.baseY, RIDGE_FAR.color, RIDGE_FAR.height, RIDGE_FAR.phase);
    drawHaze(286, 96, 0.5);
    drawHills(RIDGE_MID.parallax, RIDGE_MID.baseY, RIDGE_MID.color, RIDGE_MID.height, RIDGE_MID.phase);
    drawHills(0.22, 392, '#bfe0f2', 150);
    drawHaze(372, 74, 0.42);
    drawHills(FOREST_NEAR.parallax, FOREST_NEAR.baseY, FOREST_NEAR.color, FOREST_NEAR.height, FOREST_NEAR.phase);

    drawClouds();
    drawHills(0.45, 440, '#e6f5fd', 110);

    drawLightBeams();
    drawHaze(438, 88, 0.32);
  }

  // 冬日低角度阳光（纵向视差最弱）
  function drawSun() {
    const sunY = SUN_Y - game.camY * SUN_PARALLAX_Y;
    const glow = ctx.createRadialGradient(SUN_X, sunY, 16, SUN_X, sunY, 130);
    glow.addColorStop(0, 'rgba(255, 253, 235, 0.55)');
    glow.addColorStop(1, 'rgba(255, 253, 235, 0)');
    ctx.fillStyle = glow;
    ctx.beginPath();
    ctx.arc(SUN_X, sunY, 130, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = '#fffdf2';
    ctx.beginPath();
    ctx.arc(SUN_X, sunY, 28, 0, Math.PI * 2);
    ctx.fill();
  }

  // 层间雾带：纵向渐变、两端淡出，用来消除山脊接缝的硬边
  function drawHaze(centerY, height, alpha) {
    const g = ctx.createLinearGradient(0, centerY - height / 2, 0, centerY + height / 2);
    g.addColorStop(0, 'rgba(232, 247, 255, 0)');
    g.addColorStop(0.5, 'rgba(232, 247, 255, ' + alpha + ')');
    g.addColorStop(1, 'rgba(232, 247, 255, 0)');
    ctx.fillStyle = g;
    ctx.fillRect(0, centerY - height / 2, VIEW_W, height);
  }

  // 体积光：从太阳斜射向下的梯形光柱，alpha 极低并缓慢呼吸
  function drawLightBeams() {
    const breathe = 0.75 + 0.25 * Math.sin(game.time * 0.35);
    const sunY = SUN_Y - game.camY * SUN_PARALLAX_Y;
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    for (const beam of LIGHT_BEAMS) {
      const g = ctx.createLinearGradient(SUN_X, sunY, SUN_X + beam.dx, VIEW_H);
      g.addColorStop(0, 'rgba(255, 250, 224, ' + (beam.alpha * breathe) + ')');
      g.addColorStop(1, 'rgba(255, 250, 224, 0)');
      ctx.fillStyle = g;
      ctx.beginPath();
      ctx.moveTo(SUN_X - 14, sunY + 8);
      ctx.lineTo(SUN_X + 14, sunY + 8);
      ctx.lineTo(SUN_X + beam.dx + beam.halfW, VIEW_H);
      ctx.lineTo(SUN_X + beam.dx - beam.halfW, VIEW_H);
      ctx.closePath();
      ctx.fill();
    }
    ctx.restore();
  }

  // 淡淡的云：远景视差 + 极慢自漂，纯路径绘制、不依赖任何图片
  function drawClouds(list = CLOUDS, parallax = CLOUD_PARALLAX, color = '#ffffff') {
    const span = VIEW_W + 260;
    ctx.fillStyle = color;
    for (const c of list) {
      const raw = c.x - game.camX * parallax + game.time * c.speed;
      const x = ((raw % span) + span) % span - 130;
      const s = c.scale;
      ctx.globalAlpha = c.alpha;
      ctx.beginPath();
      ctx.ellipse(x, c.y, 42 * s, 15 * s, 0, 0, Math.PI * 2);
      ctx.ellipse(x - 30 * s, c.y + 4 * s, 24 * s, 11 * s, 0, 0, Math.PI * 2);
      ctx.ellipse(x + 32 * s, c.y + 3 * s, 27 * s, 12 * s, 0, 0, Math.PI * 2);
      ctx.ellipse(x + 6 * s, c.y - 10 * s, 22 * s, 14 * s, 0, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.globalAlpha = 1;
  }

  // 贴地物体统一的落地阴影：深蓝扁椭圆，只做视觉重量感
  function drawGroundShadow(cx, groundY, w, h) {
    ctx.fillStyle = SHADOW_COLOR;
    ctx.beginPath();
    ctx.ellipse(cx, groundY, w / 2, h / 2, 0, 0, Math.PI * 2);
    ctx.fill();
  }

  function drawHills(parallax, baseY, color, height, phase = 0) {
    const offset = game.camX * parallax;
    const ridgeBase = baseY - game.camY * parallax;   // 纵向视差：摄像机下移时山脊上移
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.moveTo(-10, VIEW_H);
    for (let x = -10; x <= VIEW_W + 30; x += 20) {
      const wx = x + offset + phase;
      const wave =
        Math.sin(wx * 0.0042) * 0.5 +
        Math.sin(wx * 0.0013 + 2.1) * 0.35 +
        Math.sin(wx * 0.0091 + 0.7) * 0.15;
      const y = ridgeBase - (wave * 0.5 + 0.5) * height;
      ctx.lineTo(x, y);
    }
    ctx.lineTo(VIEW_W + 10, VIEW_H);
    ctx.closePath();
    ctx.fill();
  }

  // 前景飘雪：屏幕空间绘制，落在世界层之上、暗角之下
  function drawSnowfall() {
    const spanX = VIEW_W + 80;
    const spanY = VIEW_H + 60;
    for (let li = 0; li < SNOWFALL.length; li++) {
      const layer = SNOWFALL[li];
      const seeds = SNOWFALL_SEEDS[li];
      ctx.fillStyle = 'rgba(255, 255, 255, ' + layer.alpha + ')';
      for (const s of seeds) {
        const x = ((s.nx * spanX - game.camX * layer.parallax + game.time * layer.drift) % spanX + spanX) % spanX - 40;
        const y = ((s.ny * spanY + game.time * layer.fall) % spanY) - 30;
        ctx.beginPath();
        ctx.arc(x, y, layer.radius, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }

  // 后期暗角：压四角、收住画面边缘
  function drawVignette() {
    ctx.fillStyle = vignetteGradient;
    ctx.fillRect(0, 0, VIEW_W, VIEW_H);
  }

  function drawPlatforms() {
    const left = game.camX - 60;
    const right = game.camX + VIEW_W + 60;
    const tilesReady = snowReady('tundraLeft') && snowReady('tundraMid') && snowReady('tundraRight');

    for (const p of LEVEL.platforms) {
      if (p.x + p.w < left || p.x > right) continue;

      if (tilesReady) {
        drawPlatformTiles(p);
      } else {
        drawPlatformFallback(p);
      }

      // 移动平台提示：雪砖之上再描一圈淡蓝边
      if (p.move) {
        ctx.strokeStyle = 'rgba(126, 200, 244, 0.95)';
        ctx.lineWidth = 2;
        roundRectPath(ctx, p.x + 1, p.y + 1, p.w - 2, p.h - 2, 5);
        ctx.stroke();
      }
    }
  }

  // 程序化雪唇：沿平台顶边画一条起伏白边，盖住贴砖的直边，弱化"平铺感"
  const SNOW_LIP_H = 7;

  function drawSnowLip(p) {
    // 20px 的浮台太薄，雪唇会把整块平台糊白，直接跳过
    if (p.h < 24) return;
    const h = Math.min(SNOW_LIP_H, p.h * 0.3);

    // 先用冷色描一遍波形：白唇叠在近白的雪面上本来分不出边界，靠这条线读出起伏
    ctx.strokeStyle = 'rgba(96, 150, 180, 0.3)';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    for (let x = 0; x <= p.w; x += 14) {
      const t = (p.x + x) * 0.045;
      const y = p.y + h * 0.45 + Math.sin(t) * 2.2 + Math.sin(t * 2.3 + 1.7) * 1.1;
      if (x === 0) ctx.moveTo(p.x, y);
      else ctx.lineTo(p.x + x, y);
    }
    ctx.stroke();

    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.moveTo(p.x, p.y + h);
    for (let x = 0; x <= p.w; x += 14) {
      const t = (p.x + x) * 0.045;
      const y = p.y + h * 0.45 + Math.sin(t) * 2.2 + Math.sin(t * 2.3 + 1.7) * 1.1;
      ctx.lineTo(p.x + x, y);
    }
    ctx.lineTo(p.x + p.w, p.y + h);
    ctx.closePath();
    ctx.fill();
  }

  function drawPlatformTiles(p) {
    ctx.save();
    ctx.beginPath();
    ctx.rect(p.x, p.y, p.w, p.h);
    ctx.clip();

    ctx.drawImage(snowArt.tundraLeft, p.x, p.y, SNOW_TILE, SNOW_TILE);
    for (let x = p.x + SNOW_TILE; x < p.x + p.w; x += SNOW_TILE) {
      ctx.drawImage(snowArt.tundraMid, x, p.y, SNOW_TILE, SNOW_TILE);
    }
    ctx.drawImage(snowArt.tundraRight, p.x + p.w - SNOW_TILE, p.y, SNOW_TILE, SNOW_TILE);

    ctx.restore();

    if (!p.ground) {
      drawFloatingPlatformDetail(p);
    }

    // 冰面反光：顶面向下渐隐的白色，薄平台按高度收窄
    const sheenH = Math.min(12, p.h * 0.5);
    const ice = ctx.createLinearGradient(0, p.y, 0, p.y + sheenH);
    ice.addColorStop(0, 'rgba(255, 255, 255, 0.55)');
    ice.addColorStop(1, 'rgba(255, 255, 255, 0)');
    ctx.fillStyle = ice;
    ctx.fillRect(p.x, p.y, p.w, sheenH);

    drawSnowLip(p);
  }

  // 浮空平台质感：淡蓝渐变压暗 + 很淡的悬浮投影 + 底部倒挂冰锥（纯代码）
  function drawFloatingPlatformDetail(p) {
    const bottom = p.y + p.h;

    // 1) 浅蓝渐变：上浅下深，把平台从天空里压出来
    const shade = ctx.createLinearGradient(0, p.y, 0, bottom);
    shade.addColorStop(0, 'rgba(120, 170, 205, 0.06)');
    shade.addColorStop(1, 'rgba(38, 74, 112, 0.30)');
    ctx.fillStyle = shade;
    roundRectPath(ctx, p.x, p.y, p.w, p.h, 6);
    ctx.fill();

    // 2) 悬浮投影：比平台窄、非常淡
    ctx.fillStyle = 'rgba(52, 92, 128, 0.10)';
    ctx.beginPath();
    ctx.ellipse(p.x + p.w / 2, bottom + 12, p.w * 0.42, 7, 0, 0, Math.PI * 2);
    ctx.fill();

    // 3) 底部倒挂冰锥：等距排列，长度交错，带一道白色高光
    const count = Math.max(2, Math.floor(p.w / 34));
    const step = p.w / count;
    const halfW = Math.min(5.5, step * 0.22);
    for (let i = 0; i < count; i++) {
      const cx = p.x + step * (i + 0.5);
      const len = 8 + (i % 3) * 4;

      ctx.fillStyle = 'rgba(196, 235, 250, 0.92)';
      ctx.beginPath();
      ctx.moveTo(cx - halfW, bottom - 1);
      ctx.lineTo(cx + halfW, bottom - 1);
      ctx.lineTo(cx, bottom + len);
      ctx.closePath();
      ctx.fill();

      ctx.fillStyle = 'rgba(255, 255, 255, 0.55)';
      ctx.beginPath();
      ctx.moveTo(cx - halfW * 0.45, bottom - 1);
      ctx.lineTo(cx + halfW * 0.1, bottom - 1);
      ctx.lineTo(cx, bottom + len * 0.62);
      ctx.closePath();
      ctx.fill();
    }
  }

  // 素材未就绪 / 加载失败时的兜底：浅色平台 + 白色雪面
  function drawPlatformFallback(p) {
    const grad = ctx.createLinearGradient(0, p.y, 0, p.y + p.h);
    grad.addColorStop(0, '#d5f2f8');
    grad.addColorStop(1, '#a9d8e4');
    ctx.fillStyle = grad;
    if (p.ground) {
      ctx.fillRect(p.x, p.y, p.w, p.h);
    } else {
      roundRectPath(ctx, p.x, p.y, p.w, p.h, 6);
      ctx.fill();

      drawFloatingPlatformDetail(p);
    }

    ctx.fillStyle = '#ffffff';
    roundRectPath(ctx, p.x + 2, p.y, p.w - 4, 6, 3);
    ctx.fill();
  }

  // 静态场景装饰：贴图底边对齐地面，不参与碰撞
  function drawDecorations() {
    const left = game.camX - SNOW_TILE;
    const right = game.camX + VIEW_W + SNOW_TILE;

    for (const d of SNOW_DECOR) {
      if (d.x + SNOW_TILE < left || d.x > right) continue;
      if (!snowReady(d.tile)) continue;

      // 树 / 石头落地阴影：按各自轮廓给一个扁椭圆（不参与碰撞）
      const cx = d.x + SNOW_TILE / 2;
      if (d.tile === 'pineSapling') {
        drawGroundShadow(cx, d.groundY, 44, 7);
      } else if (d.tile === 'rock') {
        drawGroundShadow(cx, d.groundY, 56, 8);
      }

      ctx.drawImage(snowArt[d.tile], d.x, d.groundY - SNOW_TILE, SNOW_TILE, SNOW_TILE);
    }
  }

  function drawCoins() {
    const left = game.camX - 40;
    const right = game.camX + VIEW_W + 40;
    for (const coin of LEVEL.coins) {
      if (coin.collected) continue;
      if (coin.x < left || coin.x > right) continue;

      const bob = Math.sin(game.time * 3 + coin.phase) * 4;
      // 保留自转，但压缩幅度收窄，避免侧转时被压成细条看起来像多边形
      const spin = 0.84 + 0.16 * Math.abs(Math.cos(game.time * 3 + coin.phase));
      const y = coin.y + bob;

      ctx.save();
      ctx.translate(coin.x, y);
      ctx.scale(spin, 1);

      if (artReady(coinArt, 'coin_gold')) {
        ctx.shadowColor = 'rgba(255, 210, 80, 0.85)';
        ctx.shadowBlur = 12;
        ctx.drawImage(coinArt.coin_gold, -COIN_ART / 2, -COIN_ART / 2, COIN_ART, COIN_ART);
        ctx.shadowBlur = 0;
        ctx.restore();
        continue;
      }

      ctx.shadowColor = 'rgba(255, 210, 80, 0.85)';
      ctx.shadowBlur = 14;

      // 亮黄圆币：径向提亮 + 深金收边，保证小尺寸下仍是"圆"
      const grad = ctx.createRadialGradient(-3, -4, 1, 0, 0, COIN_R);
      grad.addColorStop(0, '#fffbe0');
      grad.addColorStop(0.45, '#ffe066');
      grad.addColorStop(1, '#d99a1b');
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(0, 0, COIN_R, 0, Math.PI * 2);
      ctx.fill();

      ctx.shadowBlur = 0;

      // 内圈
      ctx.strokeStyle = 'rgba(190, 128, 20, 0.55)';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(0, 0, COIN_R - 3.6, 0, Math.PI * 2);
      ctx.stroke();

      // 外缘亮边
      ctx.strokeStyle = 'rgba(255, 244, 190, 0.9)';
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.arc(0, 0, COIN_R - 0.6, 0, Math.PI * 2);
      ctx.stroke();

      // 白色反光点
      ctx.fillStyle = 'rgba(255, 255, 255, 0.95)';
      ctx.beginPath();
      ctx.arc(-3.4, -4.2, 2.2, 0, Math.PI * 2);
      ctx.fill();

      ctx.restore();
    }
  }

  function drawFlag() {
    const f = LEVEL.flag;
    if (f.x + f.w + 40 < game.camX || f.x - 40 > game.camX + VIEW_W) return;
    const wave = Math.sin(game.time * 4) * 5;

    // 旗杆
    ctx.fillStyle = '#cbd5e1';
    ctx.fillRect(f.x + 4, f.y, 5, f.h);
    ctx.fillStyle = '#94a3b8';
    ctx.beginPath();
    ctx.arc(f.x + 6.5, f.y, 5, 0, Math.PI * 2);
    ctx.fill();

    // 旗面
    const grad = ctx.createLinearGradient(f.x, f.y, f.x + 70, f.y);
    grad.addColorStop(0, '#ff7b9c');
    grad.addColorStop(1, '#e94b76');
    ctx.fillStyle = grad;
    ctx.beginPath();
    ctx.moveTo(f.x + 9, f.y + 12);
    ctx.quadraticCurveTo(f.x + 42, f.y + 16 + wave, f.x + 74, f.y + 10 + wave * 0.6);
    ctx.lineTo(f.x + 74, f.y + 46 + wave * 0.6);
    ctx.quadraticCurveTo(f.x + 42, f.y + 52 + wave, f.x + 9, f.y + 56);
    ctx.closePath();
    ctx.fill();

    // 旗面高光
    ctx.fillStyle = 'rgba(255, 255, 255, 0.22)';
    ctx.beginPath();
    ctx.moveTo(f.x + 12, f.y + 18);
    ctx.quadraticCurveTo(f.x + 40, f.y + 22 + wave * 0.8, f.x + 68, f.y + 18 + wave * 0.5);
    ctx.lineTo(f.x + 68, f.y + 26 + wave * 0.5);
    ctx.quadraticCurveTo(f.x + 40, f.y + 30 + wave * 0.8, f.x + 12, f.y + 26);
    ctx.closePath();
    ctx.fill();
  }

  function drawEnemies() {
    const left = game.camX - 40;
    const right = game.camX + VIEW_W + 40;
    for (const e of LEVEL.enemies) {
      if (!e.alive) continue;
      if (e.x + e.w < left || e.x > right) continue;

      const bob = Math.sin(game.time * 6 + e.phase) * 1.5;
      const cy = e.y + e.h / 2 + bob;

      // 落地投影：与其它贴地物体统一的深蓝扁椭圆
      drawGroundShadow(e.x + e.w / 2, e.y + e.h + 1, e.w * 0.95, 5);

      ctx.save();
      ctx.translate(e.x + e.w / 2, cy);

      // 史莱姆贴图：两帧走路交替，底边对齐碰撞箱底部；任一帧未就绪则回退程序化小怪
      if (artReady(enemyArt, 'slime_normal_walk_a') && artReady(enemyArt, 'slime_normal_walk_b')) {
        const frame = Math.floor(game.time * ENEMY_WALK_FPS + e.phase) % 2 === 0
          ? 'slime_normal_walk_a'
          : 'slime_normal_walk_b';
        ctx.save();
        ctx.scale(e.dir, 1);
        ctx.drawImage(enemyArt[frame], -ENEMY_ART / 2, e.h / 2 - ENEMY_ART, ENEMY_ART, ENEMY_ART);
        drawSlimeGloss(e.w, e.h, e.h / 2 - ENEMY_ART);
        ctx.restore();
        ctx.restore();
        continue;
      }

      const grad = ctx.createLinearGradient(0, -e.h / 2, 0, e.h / 2);
      grad.addColorStop(0, '#f06b6b');
      grad.addColorStop(1, '#c73f3f');
      ctx.fillStyle = grad;
      ctx.strokeStyle = '#7a1f1f';
      ctx.lineWidth = 2;
      roundRectPath(ctx, -e.w / 2, -e.h / 2, e.w, e.h, 8);
      ctx.fill();
      ctx.stroke();
      drawSlimeGloss(e.w, e.h, -e.h / 2);

      // 眼睛（随朝向偏移）
      const look = e.dir * 2.5;
      ctx.fillStyle = '#ffffff';
      ctx.beginPath();
      ctx.arc(-5 + look, -3, 4.2, 0, Math.PI * 2);
      ctx.arc(6 + look, -3, 4.2, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = '#2a1010';
      ctx.beginPath();
      ctx.arc(-4.2 + look, -2.4, 1.9, 0, Math.PI * 2);
      ctx.arc(6.8 + look, -2.4, 1.9, 0, Math.PI * 2);
      ctx.fill();

      // 小嘴
      ctx.strokeStyle = 'rgba(60, 12, 12, 0.75)';
      ctx.lineWidth = 1.6;
      ctx.beginPath();
      ctx.arc(look, 5.5, 3.6, 0.15 * Math.PI, 0.85 * Math.PI);
      ctx.stroke();

      ctx.restore();
    }
  }

  // 史莱姆表面高光：两层半透明白椭圆，营造果冻质感（纯绘制，不影响碰撞）
  function drawSlimeGloss(w, h, topY) {
    ctx.fillStyle = 'rgba(255, 255, 255, 0.32)';
    ctx.beginPath();
    ctx.ellipse(-w * 0.12, topY + h * 0.3, w * 0.3, h * 0.18, -0.5, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = 'rgba(255, 255, 255, 0.5)';
    ctx.beginPath();
    ctx.ellipse(-w * 0.19, topY + h * 0.24, w * 0.12, h * 0.08, -0.5, 0, Math.PI * 2);
    ctx.fill();
  }

  function drawParticles() {
    const dustReady = artReady(fxArt, 'smoke_05');
    for (const pt of game.particles) {
      const k = pt.life / pt.max;
      if (pt.dust) {
        // 落地雪雾：素材就绪时用 Kenney smoke 贴图，否则退化为半透明圆点
        const size = 16 + (1 - k) * 18;
        ctx.globalAlpha = k * 0.5;
        if (dustReady) {
          ctx.drawImage(fxArt.smoke_05, pt.x - size / 2, pt.y - size / 2, size, size);
        } else {
          ctx.fillStyle = pt.color;
          ctx.beginPath();
          ctx.arc(pt.x, pt.y, size * 0.3, 0, Math.PI * 2);
          ctx.fill();
        }
        continue;
      }
      const size = 2.5 * k + 1;
      ctx.globalAlpha = k;
      ctx.fillStyle = pt.color || '#ffe38a';
      if (pt.square) {
        ctx.fillRect(pt.x - size, pt.y - size, size * 2, size * 2);
      } else {
        ctx.beginPath();
        ctx.arc(pt.x, pt.y, size, 0, Math.PI * 2);
        ctx.fill();
      }
    }
    ctx.globalAlpha = 1;
  }

  function drawPlayer() {
    const p = player;

    // 落地阴影：只在贴地时出现，避免空中拖着一块影子
    if (p.onGround) {
      drawGroundShadow(p.x + p.w / 2, p.y + p.h + 1, p.w * 0.95, 6);
    }

    // 突进残影：半透明绘制在角色之后，按存活时间淡出
    for (const ghost of p.dashGhosts) {
      const k = ghost.t / DASH_GHOST_LIFE;
      ctx.globalAlpha = (1 - k) * 0.38;
      ctx.save();
      ctx.translate(ghost.x + p.w / 2, ghost.y + p.h);
      ctx.scale(ghost.facing < 0 ? -1 : 1, 1);
      if (ghost.sprite) {
        drawPlayerSprite(ghost.sprite);
      } else {
        drawPlayerPlaceholder();
      }
      ctx.restore();
    }
    ctx.globalAlpha = 1;

    // 二段跳反馈：纯代码绘制的扩散圆环（不依赖任何图片）
    for (const ring of p.jumpRings) {
      const k = ring.t / JUMP_RING_LIFE;
      ctx.globalAlpha = (1 - k) * 0.6;
      ctx.strokeStyle = '#cfeeff';
      ctx.lineWidth = 3.2 * (1 - k) + 0.8;
      ctx.beginPath();
      ctx.arc(ring.x, ring.y, 10 + k * 26, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.globalAlpha = 1;

    // 无敌闪烁：与重生闪烁取"或"关系，不叠加
    if (p.invuln > 0 && Math.floor(game.time * 12) % 2 === 0) return;

    // 冷却期间角色变淡；与重生闪烁重叠时取更淡的值
    let bodyAlpha = 1;
    if (p.dashTime <= 0 && p.dashCd > 0) bodyAlpha = DASH_CD_ALPHA;
    if (p.respawnTimer > 0 && Math.floor(p.respawnTimer * 20) % 2 === 0) bodyAlpha = 0.35;
    ctx.globalAlpha = bodyAlpha;

    ctx.save();
    ctx.translate(p.x + p.w / 2, p.y + p.h);

    // 空中拉伸 / 落地恢复
    let sx = 1;
    let sy = 1;
    if (!p.onGround) {
      const stretch = clamp(p.vy / 1600, -0.16, 0.16);
      sy = 1 - stretch;
      sx = 1 + stretch * 0.8;
    }
    ctx.scale(sx * (p.facing < 0 ? -1 : 1), sy);

    const sprite = currentSprite();
    if (sprite) {
      drawPlayerSprite(sprite);
    } else {
      drawPlayerPlaceholder();
    }

    ctx.restore();
    ctx.globalAlpha = 1;
  }

  // 按落地 / 上升 / 下落 / 跑动选帧；只读 player 状态，不修改任何游戏数据
  function playerSpriteState() {
    const p = player;
    if (!p.onGround) return p.vy < 0 ? 'jump' : 'fall';
    return Math.abs(p.vx) > 20 ? 'run' : 'idle';
  }

  function currentSprite() {
    if (!USE_SPRITE_ART || spriteFailed) return null;
    const state = playerSpriteState();
    const frames = sprites[state];
    if (!frames || frames.length === 0) return null;
    const img = frames.length === 1
      ? frames[0]
      : frames[Math.floor(game.time * (SPRITE_FPS[state] || 8)) % frames.length];
    return img.complete && img.naturalWidth > 0 ? img : null;
  }

  // 整图按比例缩放，脚底对齐碰撞箱底部；碰撞箱 28×40 保持不变
  function drawPlayerSprite(img) {
    const scale = player.w / SPRITE_W;
    ctx.drawImage(
      img,
      -SPRITE_CENTER_X * scale,
      -SPRITE_FEET_Y * scale,
      player.w,
      SPRITE_H * scale
    );
  }

  // 占位图形：图片加载失败或关闭 USE_SPRITE_ART 时使用
  function drawPlayerPlaceholder() {
    const p = player;

    // 身体
    const body = ctx.createLinearGradient(0, -p.h, 0, 0);
    body.addColorStop(0, '#ff9ebb');
    body.addColorStop(1, '#e94b76');
    ctx.fillStyle = body;
    roundRectPath(ctx, -p.w / 2, -p.h, p.w, p.h, 9);
    ctx.fill();

    // 顶部高光
    ctx.fillStyle = 'rgba(255, 255, 255, 0.28)';
    roundRectPath(ctx, -p.w / 2 + 4, -p.h + 4, p.w - 8, 8, 4);
    ctx.fill();

    // 眼睛（朝右侧绘制，靠 scale 翻转）
    ctx.fillStyle = '#ffffff';
    ctx.beginPath();
    ctx.arc(2, -p.h + 16, 4.6, 0, Math.PI * 2);
    ctx.arc(11, -p.h + 16, 4.6, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = '#252b3b';
    ctx.beginPath();
    ctx.arc(3.2, -p.h + 16.6, 2.1, 0, Math.PI * 2);
    ctx.arc(12.2, -p.h + 16.6, 2.1, 0, Math.PI * 2);
    ctx.fill();

    // 腮红
    ctx.fillStyle = 'rgba(255, 255, 255, 0.35)';
    ctx.beginPath();
    ctx.ellipse(-6, -p.h + 24, 3.4, 2, 0, 0, Math.PI * 2);
    ctx.fill();
  }

  function render() {
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, VIEW_W, VIEW_H);

    drawBackground();

    ctx.save();
    ctx.translate(-Math.round(game.camX), -Math.round(game.camY));
    drawPlatforms();
    drawDecorations();
    drawCoins();
    drawEnemies();
    drawFlag();
    drawPlayer();
    drawParticles();
    ctx.restore();

    drawSnowfall();
    drawVignette();
  }

  /* ============================================================
   * 10. 主循环
   * ============================================================ */

  function update(dt) {
    game.time += dt;

    if (game.state === 'playing') {
      updateMovingPlatforms();
      carryPlayerOnPlatforms();
      updatePlayer(dt);
      updateEnemies(dt);
      updateEnemyHits();
      updateCoins();
      checkGoal();
    }

    updateParticles(dt);
    updateCamera(dt);
  }

  let lastTime = performance.now();
  let accumulator = 0;

  function frame(now) {
    let delta = (now - lastTime) / 1000;
    lastTime = now;
    if (delta > 0.1) delta = 0.1;   // 切后台回来不做长时间补帧

    accumulator += delta;

    let steps = 0;
    while (accumulator >= STEP && steps < 5) {
      update(STEP);
      accumulator -= STEP;
      steps += 1;
    }
    if (steps === 5) accumulator = 0;

    render();
    requestAnimationFrame(frame);
  }

  /* ============================================================
   * 11. 启动
   * ============================================================ */

  resizeCanvas();
  loadSprites();
  loadSnowArt();
  loadArt();
  setupTouchControls();
  setupMoveWheel();
  loadLevel(0);
  requestAnimationFrame(frame);
})();
