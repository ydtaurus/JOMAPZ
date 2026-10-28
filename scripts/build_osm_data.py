"""Bina data OSM Malaysia (POI, lampu isyarat, kamera, data lorong) ke www/data/osm.json.
Guna: python scripts/build_osm_data.py malaysia.osm.pbf www/data/osm.json"""
import json, sys, datetime
import osmium

INFRA = {
    "fuel": lambda t: t.get("amenity") == "fuel",
    "mall": lambda t: t.get("shop") == "mall",
    "toll": lambda t: t.get("barrier") == "toll_booth",
    "cam": lambda t: t.get("highway") == "speed_camera",
    "rnr": lambda t: t.get("highway") in ("services", "rest_area"),
    "lorong": lambda t: t.get("motorcycle") == "designated" and "highway" in t,
    "masjid": lambda t: t.get("amenity") == "place_of_worship" and t.get("religion") == "muslim",
    "hosp": lambda t: t.get("amenity") in ("hospital", "clinic"),
    "bengkel": lambda t: t.get("shop") in ("motorcycle_repair", "car_repair", "tyres", "motorcycle"),
    "ev": lambda t: t.get("amenity") == "charging_station",
    "atm": lambda t: t.get("amenity") == "atm",
    "polis": lambda t: t.get("amenity") == "police",
    "sekolah": lambda t: t.get("amenity") == "school",
    "sig": lambda t: t.get("highway") == "traffic_signals",
}
LANE_KEYS = ("lanes", "lanes:forward", "lanes:backward", "turn:lanes", "turn:lanes:forward",
             "turn:lanes:backward", "oneway", "junction")


def r5(x):
    return round(x, 5)


class H(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.cats = {k: [] for k in INFRA}
        self.lanes = []
        self.seen = {k: set() for k in INFRA}

    def add(self, cat, lat, lng, t):
        name = (t.get("name") or t.get("brand") or t.get("operator") or "")[:40]
        if cat == "cam":
            row = [r5(lat), r5(lng), name, (t.get("maxspeed") or "")[:6]]
        elif cat == "sig":
            row = [r5(lat), r5(lng)]
        else:
            row = [r5(lat), r5(lng), name]
        key = (row[0], row[1])
        if key in self.seen[cat]:
            return
        self.seen[cat].add(key)
        self.cats[cat].append(row)

    def node(self, n):
        if not n.tags or not n.location.valid():
            return
        t = {tg.k: tg.v for tg in n.tags}
        for cat, fn in INFRA.items():
            if cat == "lorong":
                continue
            if fn(t):
                self.add(cat, n.location.lat, n.location.lon, t)

    def way(self, w):
        if not w.tags:
            return
        t = {tg.k: tg.v for tg in w.tags}
        hit = [c for c, fn in INFRA.items() if c not in ("sig",) and fn(t)]
        has_lanes = "highway" in t and any(k.startswith("turn:lanes") for k in t)
        if not hit and not has_lanes:
            return
        pts = []
        try:
            for nd in w.nodes:
                if nd.location.valid():
                    pts.append((nd.location.lat, nd.location.lon))
        except Exception:
            return
        if not pts:
            return
        if hit:
            lat = sum(p[0] for p in pts) / len(pts)
            lng = sum(p[1] for p in pts) / len(pts)
            for c in hit:
                self.add(c, lat, lng, t)
        if has_lanes:
            self.lanes.append({"g": [[r5(a), r5(b)] for a, b in pts],
                               "t": {k: t[k] for k in LANE_KEYS if k in t}})


def main():
    src, dst = sys.argv[1], sys.argv[2]
    h = H()
    h.apply_file(src, locations=True, idx="flex_mem")
    out = {"v": datetime.date.today().isoformat(), "cats": h.cats, "lanes": h.lanes}
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    for k, v in h.cats.items():
        print(k, len(v))
    print("lane ways", len(h.lanes))


main()
