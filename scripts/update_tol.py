"""Semak harga tol terkini dan kemas kini www/tol.json jika ada perubahan.
Hanya mengemas kini plaza yang sudah ada dalam tol.json (kelas 1).
Keluar dengan kod 1 jika halaman tak dapat dibaca supaya data lama kekal."""
import datetime, json, os, re, sys
import requests
from bs4 import BeautifulSoup

URL = "https://www.motorist.my/article/2829/list-of-malaysia-highway-toll-rates-plus-klk-lpt-and-others"
PATH = os.path.join(os.path.dirname(__file__), "..", "www", "tol.json")

def money(txt):
    m = re.search(r"(\d+(?:\.\d+)?)", txt.replace(",", ""))
    return float(m.group(1)) if m else None

def scrape():
    r = requests.get(URL, timeout=40, headers={"User-Agent": "Mozilla/5.0 (JOMAPZ toll checker)"})
    r.raise_for_status()
    soup = BeautifulSoup(r.text, "lxml")
    found = {}
    for table in soup.find_all("table"):
        rows = [[c.get_text(" ", strip=True) for c in tr.find_all(["th", "td"])] for tr in table.find_all("tr")]
        hdr = None
        for i, row in enumerate(rows):
            low = [c.lower() for c in row]
            if any("toll plaza" in c for c in low) and any("class 1" in c for c in low):
                hdr = (i, [j for j, c in enumerate(low) if "toll plaza" in c][0], [j for j, c in enumerate(low) if "class 1" in c][0])
                break
        if not hdr:
            continue
        hi, pi, ci = hdr
        for row in rows[hi + 1:]:
            if len(row) <= max(pi, ci):
                continue
            price = money(row[ci])
            if price is not None:
                found[row[pi].strip().lower()] = price
    return found

def main():
    data = json.load(open(PATH, encoding="utf-8"))
    found = scrape()
    print("baris dijumpai:", len(found))
    if len(found) < 10:
        print("Halaman tak dapat dibaca dengan betul. Data lama dikekalkan.")
        sys.exit(1)
    changed, matched = [], 0
    for p in data["plazas"]:
        name = p["n"].lower()
        price = found.get(name)
        if price is None:
            cands = [v for k, v in found.items() if k == name or k.startswith(name)]
            price = cands[0] if cands else None
        if price is None:
            print("tiada dalam jadual:", p["n"])
            continue
        matched += 1
        old = p["c1"]
        if abs(price - old) < 0.005:
            continue
        if price <= 0 or price < old * 0.5 or price > old * 2:
            print("diabaikan (perubahan melampau):", p["n"], old, "->", price)
            continue
        p["c1"] = price
        changed.append("%s %.2f -> %.2f" % (p["n"], old, price))
    if matched < max(5, len(data["plazas"]) // 2):
        print("Terlalu sedikit padanan, data lama dikekalkan.")
        sys.exit(1)
    if changed:
        data["updated"] = datetime.date.today().isoformat()
        json.dump(data, open(PATH, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
        print("UBAH:", "; ".join(changed))
    else:
        print("Tiada perubahan harga.")
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as f:
            f.write("changed=%s\n" % ("true" if changed else "false"))

main()
