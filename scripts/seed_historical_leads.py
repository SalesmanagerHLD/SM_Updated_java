#!/usr/bin/env python3
"""Adds leads spread over 2023-2026 to the SunBridge demo org (run seed_demo_data.py first).

Leads are created through the REST API (so every business rule still applies); the API always stamps
created_at = now, so the dates are then back-dated directly in Postgres through `docker exec`.
Re-run safe: leads are matched by phone number (97100xxxxx) and skipped if they exist.
"""
import argparse
import random
import subprocess
import sys
from datetime import date, timedelta

import seed_demo_data as base

REPS = ["priya", "rohan", "anjali", "karan", "vikram", "neha"]
STATE_OF = {"priya": "Maharashtra", "rohan": "Maharashtra", "anjali": "Maharashtra",
            "karan": "Gujarat", "vikram": "Karnataka", "neha": "Delhi"}
PREFIXES = ["Shree", "Sai", "Om", "Bharat", "Navjeevan", "Ganesh", "Lotus", "Surya", "Western", "Royal",
            "Annapurna", "Kisan", "Metro", "Prime", "Evergreen"]
KINDS = [("Textiles", "Textile"), ("Cold Storage", "Logistics & Transportation"), ("Dairy", "Agriculture"),
         ("Hospital", "Healthcare"), ("Engineering Works", "Manufacturing"), ("Hotels", "Hospitality"),
         ("Pharma", "Pharmaceuticals"), ("School", "Education"), ("Plastics", "Manufacturing"),
         ("Rice Mills", "Agriculture"), ("Auto Components", "Automotive"), ("Foods", "Manufacturing")]
SOURCES = ["Referral", "Trade Show", "Cold Call", "Inbound Web Form", "Walk-in", "Advertisement", "Social Media"]
INTERESTS = ["Hot", "Warm", "Cold"]


def plan(count, today):
    """(created date, rep, company, industry, source, interest, final status) - older leads are mostly resolved."""
    rnd = random.Random(2024)
    start = date(2023, 1, 9)
    span = (today - timedelta(days=20) - start).days
    rows = []
    for i in range(count):
        created = start + timedelta(days=int(span * i / count) + rnd.randint(0, 12))
        age_days = (today - created).days
        rep = REPS[i % len(REPS)]
        kind, industry = KINDS[i % len(KINDS)]
        company = "%s %s #%d" % (PREFIXES[(i * 7) % len(PREFIXES)], kind, i + 1)
        roll = rnd.random()
        if age_days > 540:
            status = "LOST" if roll < 0.5 else ("CLOSED_WON" if roll < 0.8 else "CONTACTED")
        elif age_days > 180:
            status = "LOST" if roll < 0.35 else ("CLOSED_WON" if roll < 0.55 else ("NEGOTIATION" if roll < 0.75 else "CONTACTED"))
        else:
            status = None if roll < 0.35 else ("CONTACTED" if roll < 0.7 else "NEGOTIATION")
        interest = "Hot" if status in ("CLOSED_WON", "NEGOTIATION") else rnd.choice(INTERESTS)
        rows.append((created, rep, company, industry, rnd.choice(SOURCES), interest, status))
    return rows


def psql(sql):
    out = subprocess.run(["docker", "exec", "-i", "salesmanager-db", "psql", "-U", "salesmanager", "-d",
                          "salesmanager", "-v", "ON_ERROR_STOP=1", "-t", "-A"],
                         input=sql, capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("psql failed: " + out.stderr)
    return out.stdout


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base-url", default="http://localhost:8080/api/v1")
    ap.add_argument("--platform-key", default="dev-only-platform-key-change-me")
    ap.add_argument("--count", type=int, default=48)
    args = ap.parse_args()

    s = base.Seeder(base.Api(args.base_url, args.platform_key))
    s.bootstrap_org()
    for rep in REPS:
        email = "%s@sunbridge.demo" % rep
        status, data = s.api.call("POST", "/auth/login", None, {"email": email, "password": base.DEMO_PASSWORD})
        if status != 200:
            sys.exit("Cannot log in %s (%s) - run seed_demo_data.py first" % (email, status))
        s.tokens[rep] = data["accessToken"]

    today = date.today()
    existing = {l["contactNo"] for l in s.must("GET", "/leads?size=500", s.tokens["admin"]).get("content", [])}
    backdate = []
    for i, (created, rep, company, industry, source, interest, status) in enumerate(plan(args.count, today)):
        phone = "97100%05d" % (i + 1)
        if phone in existing:
            continue
        city, state_id = s.city_in(STATE_OF[rep], i)
        body = {"companyName": company, "contactPerson": "Contact %d" % (i + 1), "contactNo": phone,
                "email": "%s@lead.demo" % phone, "stateId": state_id, "cityId": city,
                "industryId": s.pick("INDUSTRY", industry), "leadSourceId": s.pick("LEAD_SOURCE", source),
                "interestLevelId": s.pick("INTEREST_LEVEL", interest),
                "designationId": s.pick("DESIGNATION", "Owner"),
                "requirements": "Rooftop solar, about %d kW." % (15 + i * 3),
                "budgetRange": "%d-%d lakh" % (4 + i % 7, 8 + i % 7), "turnover": 3000000 + i * 900000,
                "productIds": [s.pick("PRODUCT", "Solar Panel 550W")]}
        lead = s.must("POST", "/leads", s.tokens[rep], body, company)
        path = "/leads/%s/status" % lead["id"]
        if status == "LOST":
            s.soft("PATCH", path, s.tokens[rep], {"status": "LOST", "lostReasonId":
                   s.pick("LOST_REASON", base.LOST_REASONS[i % len(base.LOST_REASONS)])}, company)
        elif status:
            if status == "CLOSED_WON":
                s.soft("PATCH", path, s.tokens[rep], {"status": "NEGOTIATION"}, company)
            s.soft("PATCH", path, s.tokens[rep], {"status": status}, company)
        backdate.append((lead["id"], created))

    if backdate:
        values = ",".join("('%s'::uuid, timestamptz '%s 10:30:00+05:30')" % (lid, d.isoformat())
                          for lid, d in backdate)
        psql("SET app.bypass_rls = 'on';\n"
             "UPDATE leads l SET created_at = v.d, updated_at = v.d + interval '3 days' "
             "FROM (VALUES %s) AS v(id, d) WHERE l.id = v.id;" % values)
    print("Created and back-dated %d leads (2023 - %s)" % (len(backdate), today.year))
    for w in s.warnings:
        print("warning:", w)


if __name__ == "__main__":
    main()
