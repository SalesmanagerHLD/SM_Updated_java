#!/usr/bin/env python3
"""Adds customer purchase orders to the SunBridge demo org (run seed_demo_data.py first).

One PO in each state - Received, Accepted, Partly billed, Fully billed, Cancelled - plus one whose
rate differs from its quotation, one belonging to an employee (Rohan), and one billed past its
quantity by an admin override. Re-run safe: a PO number that already exists is skipped.
"""
import argparse
import sys
from datetime import date, timedelta

import seed_demo_data as base


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--base-url", default="http://localhost:8080/api/v1")
    ap.add_argument("--platform-key", default="dev-only-platform-key-change-me")
    args = ap.parse_args()

    s = base.Seeder(base.Api(args.base_url, args.platform_key))
    s.bootstrap_org()
    s.grant_entitlements()
    st, data = s.api.call("POST", "/auth/login", None, {"email": "rohan@sunbridge.demo", "password": base.DEMO_PASSWORD})
    if st != 200:
        sys.exit("Run seed_demo_data.py first (cannot log in rohan)")
    s.tokens["rohan"] = data["accessToken"]
    admin = s.tokens["admin"]

    products = {p["name"]: p for p in s.must("GET", "/inventory/products?size=100", admin).get("content", [])}
    leads = {l["companyName"]: l for l in s.must("GET", "/leads?size=200", admin).get("content", [])}
    existing = {p["poNumber"] for p in s.must("GET", "/purchase-orders?size=200", admin).get("content", [])}
    today = date.today()

    def line(name, qty, rate, tax="12", catalog=True, qline=None):
        p = products.get(name)
        body = {"description": name, "quantity": qty, "rate": rate, "taxRatePercent": tax,
                "unit": (p or {}).get("unitOfMeasure") or "NOS", "hsnSac": (p or {}).get("hsnSacCode")}
        if catalog and p:
            body["productId"] = p["id"]
        if qline:
            body["quotationLineId"] = qline
        return body

    def make(token, company, number, lines, days_ago, terms=30, accept=True, quotation=None, address=None):
        if number in existing:
            return None
        lead = leads[company]
        body = {"leadId": lead["id"], "poNumber": number, "poDate": (today - timedelta(days=days_ago)).isoformat(),
                "deliveryDate": (today + timedelta(days=20)).isoformat(), "paymentTermsDays": terms,
                "customerName": company, "billingAddress": address or "Plot 14, MIDC Industrial Area",
                "notes": "Delivery in two lots if possible.", "lineItems": lines}
        if quotation:
            body["quotationId"] = quotation
        po = s.must("POST", "/purchase-orders", token, body, number)
        if accept:
            po = s.must("POST", "/purchase-orders/%s/accept" % po["id"], token, None, number)
        return po

    def bill(token, po, quantities, reason=None):
        body = s.must("POST", "/purchase-orders/%s/invoice" % po["id"], token, None, po["poNumber"])
        picked = []
        for item in body["lineItems"]:
            idx = [l["id"] for l in po["lineItems"]].index(item["poLineId"])
            if quantities[idx]:
                picked.append({"poLineId": item["poLineId"], "quantity": quantities[idx]})
        req = {"customerName": body["customerName"], "customerAddress": body["customerAddress"],
               "invoiceDate": body["invoiceDate"], "leadId": body["leadId"], "poId": po["id"], "lineItems": picked}
        if reason:
            req["overBillingReason"] = reason
        return s.must("POST", "/invoices", token, req, po["poNumber"])

    # 1. Received (not yet accepted)
    make(admin, "Bhoomi Agro Foods", "BAF/PO/2026/101",
         [line("Solar Panel 330W", 60, "9800.00"), line("Installation Service", 1, "42000.00", "18", catalog=False)],
         2, accept=False)
    # 2. Accepted, nothing billed yet
    make(admin, "Sai Krupa Hospital", "SKH-PO-4471",
         [line("Solar Inverter 10KVA", 2, "92000.00"), line("Battery Backup", 1, "14500.00", "18")], 5)
    # 3. Partly billed: the first lot of panels is invoiced
    po = make(admin, "Trimurti Cold Chain", "TCC/2026/PO-33",
              [line("Solar Panel 550W", 30, "15900.00"), line("Solar Inverter 5KVA", 2, "46500.00")], 12, terms=45)
    if po:
        bill(admin, po, [20, 0])
    # 4. Fully billed in two invoices
    po = make(admin, "Greenway Logistics", "GWL-7781",
              [line("Solar Panel 330W", 10, "9800.00"), line("Installation Service", 1, "6500.00", "18", catalog=False)],
              25, terms=15)
    if po:
        bill(admin, po, [6, 1])
        po = s.must("GET", "/purchase-orders/%s" % po["id"], admin)
        bill(admin, po, [4, 0])
    # 5. Cancelled before any billing
    po = make(admin, "Vasant Hotels", "VH/PO/009", [line("Solar Inverter 3KVA", 3, "28500.00")], 30)
    if po:
        s.must("POST", "/purchase-orders/%s/cancel" % po["id"], admin, {"reason": "Customer postponed the project"})
    # 6. Rate differs from the quotation (flagged, not blocked), linked to quotation QT-2026-0005
    quotations = {q["quotationNumber"]: q for q in s.must("GET", "/quotations?size=50", admin).get("content", [])}
    q = quotations.get("QT-2026-0005")
    if q and q.get("leadId"):
        company = next(c for c, l in leads.items() if l["id"] == q["leadId"])
        panels = next(l for l in q["lineItems"] if l["description"] == "Solar Panel 550W")
        make(admin, company, "SDC/PO/2026/58",
             [line("Solar Panel 550W", 12, "15200.00", qline=panels["id"])], 8, quotation=q["id"])
    # 7. An employee's own PO (Rohan): visible to him and the admin only
    make(s.tokens["rohan"], "Kaveri Rice Mills", "KRM-PO-1203", [line("Solar Panel 550W", 8, "15900.00")], 3)
    # 8. Billed past its quantity by an admin override
    po = make(admin, "Mysore Silk Emporium", "MSE/PO/771",
              [line("Solar Inverter 3KVA", 2, "28500.00")], 6)
    if po:
        bill(admin, po, [3], reason="Customer asked for one spare inverter on the same PO")

    rows = s.must("GET", "/purchase-orders?size=200", admin).get("content", [])
    print("Purchase orders now: %d" % len(rows))
    for p in sorted(rows, key=lambda p: p["poNumber"]):
        print("  %-18s %-13s %-28s %10s%s" % (p["poNumber"], p["status"], p["customerName"], p["grandTotal"],
                                             "  (price differs from quotation)" if p["hasPriceDifference"] else ""))


if __name__ == "__main__":
    main()
