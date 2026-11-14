# Worked queries

All of these start from the app's process; wrap them in the prelude below.

```sql
with p as (select upid, pid, start_ts from process where name = 'ee.schimke.livery.baselineprofile.host' order by start_ts desc limit 1),
t0 as (select coalesce((select min(ts) from slice where name like 'MetricsLogger:launchObserverNotifyIntentStarted'), (select start_ts from p)) t),
m as (select s.* from slice s join thread_track tt on s.track_id = tt.id join thread th using (utid)
      where th.upid = (select upid from p) and th.tid = (select pid from p)),
r as (select s.* from slice s join thread_track tt on s.track_id = tt.id join thread th using (utid)
      where th.upid = (select upid from p) and th.name = 'RenderThread')
```

Startup timeline: every slice over 1 ms, in launch order, relative to the intent.

```sql
select round((ts - (select t from t0)) / 1e6, 1) at_ms, round(dur / 1e6, 1) dur_ms, 'main' thread, substr(name, 1, 70) name from m where dur >= 1000000
union all select round((ts - (select t from t0)) / 1e6, 1), round(dur / 1e6, 1), 'render', substr(name, 1, 70) from r where dur >= 1000000
order by 1;
```

What is on the main thread during a fling, by total time (depth >= 2 skips the frame wrappers):

```sql
select name, count(*) cnt, round(sum(dur) / 1e6, 1) total_ms, round(avg(dur) / 1e3) avg_us
from m where depth >= 2 group by name order by total_ms desc limit 25;
```

How many composition passes a data change takes (one `Compose:recompose` per pass):

```sql
select count(*) from m where name = 'Compose:recompose';
```

Which threads were busy in a window (only traced slices count; coroutine workers often show nothing):

```sql
select th.name, th.tid, round(sum(case when s.depth = 0 then s.dur end) / 1e6, 1) busy_ms
from slice s join thread_track tt on s.track_id = tt.id join thread th using (utid)
where th.upid = (select upid from p) group by th.tid order by busy_ms desc limit 10;
```

The frame timeline (jank classification) is in `actual_frame_timeline_slice` when the trace has it; the
benchmark's own `frameOverrunMs` comes from there.
