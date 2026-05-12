/* charts.js — ECharts initialisation for the Dependency Analysis Report */

const SEVERITY_COLORS = ['#b71c1c', '#e65100', '#f57f17', '#1565c0', '#2e7d32'];

/* ── Severity Pie Chart ─────────────────────────────────────────────── */
(function initSeverityChart() {
    const el = document.getElementById('severityChart');
    if (!el || typeof echarts === 'undefined') return;
    const chart = echarts.init(el);
    const data = severityLabels.map((label, i) => ({
        name: label,
        value: severityValues[i],
        itemStyle: { color: SEVERITY_COLORS[i] }
    })).filter(d => d.value > 0);

    chart.setOption({
        tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
        legend: { bottom: 0, left: 'center', type: 'scroll' },
        series: [{
            type: 'pie',
            radius: ['40%', '70%'],
            avoidLabelOverlap: false,
            label: { show: true, formatter: '{b}\n{c}' },
            data: data.length ? data : [{ name: 'No Data', value: 1, itemStyle: { color: '#ccc' } }]
        }]
    });
    window.addEventListener('resize', () => chart.resize());
})();

/* ── Updates Bar Chart ──────────────────────────────────────────────── */
(function initUpdatesChart() {
    const el = document.getElementById('updatesChart');
    if (!el || typeof echarts === 'undefined') return;
    const chart = echarts.init(el);

    chart.setOption({
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
        grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
        xAxis: {
            type: 'value',
            minInterval: 1
        },
        yAxis: {
            type: 'category',
            data: updatesLabels,
            axisLabel: { fontSize: 11 }
        },
        series: [{
            type: 'bar',
            data: updatesValues.map((v, i) => ({
                value: v,
                itemStyle: { color: SEVERITY_COLORS[i] }
            })),
            label: { show: true, position: 'right' }
        }]
    });
    window.addEventListener('resize', () => chart.resize());
})();

/* ── Table Filter ───────────────────────────────────────────────────── */
function filterTable() {
    const search   = document.getElementById('searchInput').value.toLowerCase();
    const severity = document.getElementById('severityFilter').value;
    const rows     = document.querySelectorAll('#depTable tbody tr');

    rows.forEach(row => {
        const text = row.textContent.toLowerCase();
        const sev  = row.getAttribute('data-severity') || '';
        const matchSearch   = !search   || text.includes(search);
        const matchSeverity = !severity || sev === severity;
        row.style.display = matchSearch && matchSeverity ? '' : 'none';
    });
}

/* ── Table Sort ─────────────────────────────────────────────────────── */
let sortAsc = true;
let lastSortCol = -1;

function sortTable(colIndex) {
    const table = document.getElementById('depTable');
    const tbody = table.querySelector('tbody');
    const rows  = Array.from(tbody.querySelectorAll('tr'));

    if (lastSortCol === colIndex) sortAsc = !sortAsc;
    else { sortAsc = true; lastSortCol = colIndex; }

    rows.sort((a, b) => {
        const aText = a.cells[colIndex]?.textContent.trim() ?? '';
        const bText = b.cells[colIndex]?.textContent.trim() ?? '';
        return sortAsc ? aText.localeCompare(bText) : bText.localeCompare(aText);
    });

    rows.forEach(r => tbody.appendChild(r));
}
