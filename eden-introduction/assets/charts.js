(function () {
  if (typeof echarts === 'undefined') return;

  var style = getComputedStyle(document.documentElement);
  var accent = style.getPropertyValue('--accent').trim();
  var accent2 = style.getPropertyValue('--accent2').trim();
  var ink = style.getPropertyValue('--ink').trim();
  var muted = style.getPropertyValue('--muted').trim();
  var rule = style.getPropertyValue('--rule').trim();
  var bg2 = style.getPropertyValue('--bg2').trim();

  function horizontalBar(id, categories, values, unit, max, color) {
    var el = document.getElementById(id);
    if (!el) return;
    var chart = echarts.init(el, null, { renderer: 'svg' });
    chart.setOption({
      animation: false,
      tooltip: {
        appendToBody: true,
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        backgroundColor: bg2,
        borderColor: rule,
        textStyle: { color: ink, fontSize: 12 },
        formatter: function (p) { return p[0].name + '：' + p[0].value + unit; }
      },
      grid: { left: 58, right: 46, top: 10, bottom: 22 },
      xAxis: {
        type: 'value',
        max: max,
        axisLabel: { color: muted, fontSize: 10 },
        splitLine: { lineStyle: { color: rule } },
        axisLine: { show: false },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'category',
        data: categories,
        axisLabel: { color: ink, fontSize: 11 },
        axisLine: { lineStyle: { color: rule } },
        axisTick: { show: false }
      },
      series: [{
        type: 'bar',
        data: values,
        barWidth: '52%',
        itemStyle: { color: color, borderRadius: [0, 4, 4, 0] },
        label: {
          show: true,
          position: 'right',
          color: accent2,
          fontSize: 11,
          formatter: '{c}' + unit
        }
      }]
    });
    window.addEventListener('resize', function () { chart.resize(); });
  }

  horizontalBar(
    'chart-yen',
    ['小游戏', '专注', '聊天', '摸头', '签到', '登录'],
    [50, 60, 30, 10, 40, 5],
    ' 円',
    70,
    accent
  );

  horizontalBar(
    'chart-affection',
    ['投喂', '聊天', '专注', '小游戏', '摸头'],
    [10, 5, 4, 3, 1],
    '',
    12,
    accent2
  );

  horizontalBar(
    'chart-memory',
    ['使用频次', '新鲜度', '重要度', '相关度'],
    [0.1, 0.2, 0.2, 0.5],
    '',
    0.6,
    accent
  );
})();
