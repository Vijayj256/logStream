import { useEffect, useRef } from "react";
import * as echarts from "echarts";

// Week 3: interactive histogram of log volume over time (Apache ECharts).
function Histogram({ data }) {
  const boxRef = useRef(null);
  const chartRef = useRef(null);

  useEffect(() => {
    const chart = echarts.init(boxRef.current);
    chartRef.current = chart;
    const onResize = () => chart.resize();
    window.addEventListener("resize", onResize);
    return () => {
      window.removeEventListener("resize", onResize);
      chart.dispose();
      chartRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (!chartRef.current || !data) return;
    const points = data.points || [];
    const showDate = points.length > 0 && (points[points.length - 1].time - points[0].time) > 24 * 3600 * 1000;
    const labels = points.map((p) =>
      new Date(p.time).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
    );
    chartRef.current.setOption({
      animation: false,
      grid: { left: 48, right: 20, top: 24, bottom: 64 },
      tooltip: {
        trigger: "axis",
        formatter: (items) => {
          const i = items[0].dataIndex;
          const start = new Date(points[i].time);
          const end = new Date(points[i].time + data.bucketMinutes * 60000);
          const t = (d) => d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
          return `${showDate ? start.toLocaleDateString() + " " : ""}${t(start)} - ${t(end)}<br/><b>${points[i].count.toLocaleString()}</b> logs`;
        },
      },
      xAxis: { type: "category", data: labels, axisLabel: { hideOverlap: true } },
      yAxis: { type: "value", minInterval: 1, name: "logs" },
      dataZoom: [{ type: "inside" }, { type: "slider", height: 18, bottom: 8 }],
      series: [{ type: "bar", data: points.map((p) => p.count), itemStyle: { color: "#4f46e5" } }],
    });
  }, [data]);

  return (
    <div className="card">
      <div className="card-title">
        <span>Log volume over time</span>
        {data && (
          <span className="muted">
            {data.total.toLocaleString()} logs, {data.bucketMinutes}-minute buckets - took {data.tookMs} ms
          </span>
        )}
      </div>
      <div ref={boxRef} className="chart" />
    </div>
  );
}

export default Histogram;
