# Count VRS standing-data routes and measure compact encodings.
# Usage: git clone --depth 1 --filter=blob:none --sparse https://github.com/vradarserver/standing-data vrs \
#          && git -C vrs sparse-checkout set routes
#        python3 -I vrs.py vrs OUT_DIR
import sys, glob, os, csv, gzip, lzma, sqlite3, re, collections, io
root = sys.argv[1]; out = sys.argv[2]
rows = []
for f in glob.glob(os.path.join(root, "routes/schema-01/*/*.csv")):
    with open(f, encoding="utf-8-sig") as fh:
        for r in csv.DictReader(fh):
            rows.append((r["Callsign"], r["AirlineCode"], r["Number"], r["AirportCodes"]))
print("route rows:", len(rows), "unique callsigns:", len({r[0] for r in rows}))
legs = collections.Counter(len(r[3].split("-")) for r in rows)
print("airports per route (2=direct, >2=multi-stop):", sorted(legs.items())[:8])
alnum = sum(1 for r in rows if not r[2].isdigit())
print("callsigns whose flight part is not purely numeric:", alnum, f"({alnum/len(rows):.1%})")
compact = "\n".join(f"{c},{a}" for c, _, _, a in sorted(rows)).encode()
print(f"compact csv: {len(compact)/1e6:.1f} MB raw, gzip {len(gzip.compress(compact,9))/1e6:.2f} MB, xz {len(lzma.compress(compact, preset=9))/1e6:.2f} MB")
db = os.path.join(out, "routes.db")
if os.path.exists(db): os.remove(db)
con = sqlite3.connect(db)
con.execute("create table r(callsign text primary key, airports text) without rowid")
con.executemany("insert or replace into r values(?,?)", [(c, a) for c, _, _, a in rows])
con.commit(); con.execute("vacuum"); con.close()
sz = os.path.getsize(db)
print(f"sqlite (callsign PK, without rowid): {sz/1e6:.1f} MB, gzip {len(gzip.compress(open(db,'rb').read(),9))/1e6:.2f} MB")
