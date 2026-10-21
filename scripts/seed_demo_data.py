#!/usr/bin/env python3
"""Seeds trial/demo data into a running SalesManager backend, through its REST API (so tenant
isolation, validation and the normal business rules all apply - nothing is written to the
database directly).

Creates one demo organization ("SunBridge Solar Pvt Ltd", a solar equipment business, matching
the product master data every org is seeded with) with:
  * 1 admin + 6 employees in a small hierarchy (1 manager with 3 reports, 2 more reps)
  * a billing profile, products with stock, customers (some interstate, so IGST shows up)
  * ~30 leads across employees in every status, visits, leave types / requests, attendance
  * quotations in every status (some converted to invoices), direct invoices with part,
    full and no payment, one overdue, one cancelled

Usage:
    python3 scripts/seed_demo_data.py [--base-url http://localhost:8080/api/v1]
                                       [--platform-key dev-only-platform-key-change-me]

Safe to re-run: it stops if the demo organization already has quotations and direct invoices;
if an earlier run stopped part-way, products, employees and customers that already exist are reused.
Logins are printed at the end. All passwords are the same, DEMO_PASSWORD below.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request
from datetime import date, timedelta

DEMO_PASSWORD = "Demo@1234"
ORG_NAME = "SunBridge Solar Pvt Ltd"
SUBDOMAIN = "sunbridge-demo"
ADMIN_EMAIL = "admin@sunbridge.demo"

EMPLOYEES = [
    # key, name, phone, manager key, state, city-state, designation
    ("priya", "Priya Sharma", "9820011001", None, "Maharashtra", "Manager"),
    ("rohan", "Rohan Mehta", "9820011002", "priya", "Maharashtra", "Engineer"),
    ("anjali", "Anjali Verma", "9820011003", "priya", "Maharashtra", "Consultant"),
    ("karan", "Karan Patel", "9820011004", "priya", "Gujarat", "Supervisor"),
    ("vikram", "Vikram Singh", "9820011005", None, "Karnataka", "Engineer"),
    ("neha", "Neha Gupta", "9820011006", None, "Delhi", "Procurement Officer"),
]

PRODUCTS = [
    # name (must match product master seeded for the org), sku, price, tax%, stock, hsn, unit.
    # Services get a large "stock" because every catalog line deducts stock when invoiced.
    ("Solar Inverter 3KVA", "INV-3KVA", "28500.00", "12", 40, "85044090", "NOS"),
    ("Solar Inverter 5KVA", "INV-5KVA", "46500.00", "12", 25, "85044090", "NOS"),
    ("Solar Inverter 10KVA", "INV-10KVA", "92000.00", "12", 12, "85044090", "NOS"),
    ("Solar Panel 330W", "PNL-330W", "9800.00", "12", 150, "85414011", "NOS"),
    ("Solar Panel 550W", "PNL-550W", "15900.00", "12", 120, "85414011", "NOS"),
    ("Battery Backup", "BAT-150AH", "14500.00", "18", 3, "85078000", "NOS"),  # low stock on purpose
    ("Installation Service", "SVC-INSTALL", "6500.00", "18", 9999, "998719", "JOB"),
    ("Annual Maintenance Contract", "SVC-AMC", "12000.00", "18", 9999, "998719", "YEAR"),
]

CUSTOMERS = [
    # name, contact, phone, email, state, gstin
    ("Greenfield Agro Industries", "Mahesh Kulkarni", "9890100101", "mahesh@greenfield.demo", "Maharashtra", "27AAACG1234F1Z5"),
    ("Shreeji Textiles Pvt Ltd", "Bhavesh Shah", "9825100102", "bhavesh@shreeji.demo", "Gujarat", "24AABCS5678K1Z2"),
    ("Namma Cold Storage", "Ramesh Gowda", "9845100103", "ramesh@namma.demo", "Karnataka", "29AAECN4321P1Z9"),
    ("Capital Plastics", "Sunil Arora", "9811100104", "sunil@capital.demo", "Delhi", "07AABCC8765D1Z3"),
    ("Sahyadri Dairy Co-op", "Anita Pawar", "9822100105", "anita@sahyadri.demo", "Maharashtra", "27AAAAS1111A1Z7"),
    ("Orchid Hospital Trust", "Dr. Meera Iyer", "9880100106", "meera@orchid.demo", "Karnataka", "29AABTO2222B1Z6"),
    ("Maruti Steel Works", "Dinesh Rao", "9890100107", "dinesh@maruti.demo", "Maharashtra", None),
    ("Heritage Resorts", "Pooja Malhotra", "9810100108", "pooja@heritage.demo", "Delhi", "07AAACH3333C1Z4"),
]

# company, contact, 10-digit phone, state, city index in state, interest, target status, rep key, industry, source
LEADS = [
    ("Bhoomi Agro Foods", "Suresh Jadhav", "9700000001", "Maharashtra", "Hot", "NEGOTIATION", "rohan", "Manufacturing", "Referral"),
    ("Trimurti Cold Chain", "Amit Deshmukh", "9700000002", "Maharashtra", "Hot", "CONTACTED", "rohan", "Logistics & Transportation", "Trade Show"),
    ("Kaveri Rice Mills", "Lakshmi Narayan", "9700000003", "Karnataka", "Warm", None, "rohan", "Agriculture", "Cold Call"),
    ("Pune Poly Packs", "Nitin Kale", "9700000004", "Maharashtra", "Cold", "LOST", "rohan", "Manufacturing", "Cold Outreach"),
    ("Sai Krupa Hospital", "Dr. Rajesh Patil", "9700000005", "Maharashtra", "Hot", "NEGOTIATION", "anjali", "Healthcare", "Inbound Web Form"),
    ("Vasant Hotels", "Rohit Bhosale", "9700000006", "Maharashtra", "Warm", None, "anjali", "Hospitality", "Referral"),
    ("Nashik Grape Growers", "Sandeep More", "9700000007", "Maharashtra", "Hot", "CONTACTED", "anjali", "Agriculture", "Trade Show"),
    ("Greenway Logistics", "Faisal Shaikh", "9700000008", "Maharashtra", "Cold", None, "anjali", "Logistics & Transportation", "Social Media"),
    ("Ahmedabad Denim Mills", "Jignesh Desai", "9700000009", "Gujarat", "Hot", "NEGOTIATION", "karan", "Textile", "Existing Customer Referral"),
    ("Surat Diamond Works", "Hiren Modi", "9700000010", "Gujarat", "Warm", None, "karan", "Manufacturing", "Referral"),
    ("Rajkot Engineering", "Paresh Vaghela", "9700000011", "Gujarat", "Hot", "CONTACTED", "karan", "Manufacturing", "Walk-in"),
    ("Vadodara Chemicals", "Kunal Trivedi", "9700000012", "Gujarat", "Cold", "LOST", "karan", "Chemicals", "Advertisement"),
    ("Mysore Silk Emporium", "Geetha Rao", "9700000013", "Karnataka", "Hot", "NEGOTIATION", "vikram", "Textile", "Referral"),
    ("Bengaluru Biotech Park", "Dr. Anil Kumar", "9700000014", "Karnataka", "Warm", None, "vikram", "Pharmaceuticals", "Inbound Web Form"),
    ("Hubli Foundry", "Basavaraj Patil", "9700000015", "Karnataka", "Hot", "CONTACTED", "vikram", "Manufacturing", "Cold Call"),
    ("Mangalore Fisheries Co-op", "Joseph D'Souza", "9700000016", "Karnataka", "Cold", None, "vikram", "Agriculture", "Social Media"),
    ("Noida Electronics Hub", "Gaurav Mittal", "9700000017", "Delhi", "Hot", "NEGOTIATION", "neha", "Manufacturing", "Trade Show"),
    ("Okhla Garments", "Sanjay Khanna", "9700000018", "Delhi", "Warm", None, "neha", "Textile", "Referral"),
    ("Connaught Cafe Chain", "Ritu Bansal", "9700000019", "Delhi", "Cold", "LOST", "neha", "Hospitality", "Cold Outreach"),
    ("Dwarka Public School", "Mrs. Sunita Chawla", "9700000020", "Delhi", "Hot", "CONTACTED", "neha", "Education", "Walk-in"),
    ("Thane Pharma Labs", "Dr. Vivek Joshi", "9700000021", "Maharashtra", "Hot", "NEGOTIATION", "priya", "Pharmaceuticals", "Partner/Channel"),
    ("Navi Mumbai Warehousing", "Imran Qureshi", "9700000022", "Maharashtra", "Warm", None, "priya", "Logistics & Transportation", "Referral"),
    ("Kolhapur Sugar Factory", "Prakash Patil", "9700000023", "Maharashtra", "Hot", "CONTACTED", "priya", "Agriculture", "Trade Show"),
    ("Aurangabad Auto Parts", "Mohan Rathod", "9700000024", "Maharashtra", "Cold", None, "priya", "Automotive", "Email Campaign"),
    ("Pune Software Park", "Kavita Nair", "9700000025", "Maharashtra", "Warm", None, "rohan", "IT Services", "Inbound Web Form"),
    ("Alibaug Beach Resorts", "Savio Fernandes", "9700000026", "Maharashtra", "Hot", "CONTACTED", "anjali", "Hospitality", "Referral"),
]

LOST_REASONS = ["Budget Cut", "Went With Competitor", "Price Too High", "Timing Not Right"]


class Api:
    def __init__(self, base, platform_key):
        self.base = base.rstrip("/")
        self.platform_key = platform_key

    def call(self, method, path, token=None, body=None, headers=None, ok=(200, 201)):
        req = urllib.request.Request(self.base + path, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        data = json.dumps(body).encode() if body is not None else None
        try:
            with urllib.request.urlopen(req, data) as resp:
                raw = resp.read().decode()
                return resp.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as e:
            raw = e.read().decode()
            try:
                parsed = json.loads(raw)
            except ValueError:
                parsed = raw
            return e.code, parsed


class Seeder:
    def __init__(self, api):
        self.api = api
        self.tokens = {}
        self.ids = {}
        self.masters = {}
        self.warnings = []

    # ---- helpers ----
    def must(self, method, path, token, body=None, what=""):
        status, data = self.api.call(method, path, token, body)
        if status >= 300:
            sys.exit("FAILED %s %s -> %s %s %s" % (method, path, status, what, json.dumps(data)[:400]))
        return data

    def soft(self, method, path, token, body=None, what=""):
        status, data = self.api.call(method, path, token, body)
        if status >= 300:
            self.warnings.append("%s %s -> %s %s" % (what or method, path, status, json.dumps(data)[:200]))
            return None
        return data

    def master(self, mtype, token=None):
        if mtype not in self.masters:
            self.masters[mtype] = self.must("GET", "/masters/" + mtype, token or self.tokens["admin"])
        return self.masters[mtype]

    def pick(self, mtype, label=None):
        rows = self.master(mtype)
        if label:
            for r in rows:
                if r["label"].lower() == label.lower():
                    return r["id"]
            for r in rows:
                if label.lower() in r["label"].lower():
                    return r["id"]
        return rows[0]["id"]

    def city_in(self, state_label, index=0):
        state_id = self.pick("STATE", state_label)
        cities = [c for c in self.master("CITY") if c.get("parentId") == state_id]
        return (cities[index % len(cities)]["id"] if cities else self.pick("CITY")), state_id

    # ---- steps ----
    def bootstrap_org(self):
        status, data = self.api.call("POST", "/auth/register-organization", None, {
            "organizationName": ORG_NAME, "subdomain": SUBDOMAIN, "adminFullName": "Arjun Kapoor",
            "adminEmail": ADMIN_EMAIL, "adminPassword": DEMO_PASSWORD})
        if status == 201:
            org_id = data["orgId"]
            self.tokens["admin"] = data["accessToken"]
            print("Created organization %s" % ORG_NAME)
        else:
            status, data = self.api.call("POST", "/auth/login", None,
                                         {"email": ADMIN_EMAIL, "password": DEMO_PASSWORD})
            if status != 200:
                sys.exit("Could not register or log in the demo org (%s): %s" % (status, data))
            org_id = data["orgId"]
            self.tokens["admin"] = data["accessToken"]
            # Quotations are created by the LAST step, so any quotation means a complete earlier run.
            print("Demo organization exists - resuming wherever an earlier run stopped")
        self.org_id = org_id

    def grant_entitlements(self):
        for code in ("INVOICING", "INVENTORY_MANAGEMENT", "TEAM_VISIBILITY", "EMPLOYEE_LEAVE_MANAGEMENT"):
            status, data = self.api.call(
                "PATCH", "/internal/organizations/%s/entitlements/%s" % (self.org_id, code), None,
                {"action": "GRANT", "grantedBy": "seed_demo_data"},
                headers={"X-Platform-Key": self.api.platform_key})
            if status != 200:
                sys.exit("Could not grant %s (%s) - check --platform-key: %s" % (code, status, data))
        print("Granted INVOICING, INVENTORY_MANAGEMENT, TEAM_VISIBILITY, EMPLOYEE_LEAVE_MANAGEMENT")

    def billing_profile(self):
        _, mh = self.city_in("Maharashtra")
        self.must("PUT", "/organizations/me/billing-profile", self.tokens["admin"], {
            "billingAddress": "Unit 4, Sunrise Business Park, Baner, Pune 411045",
            "billingGstin": "27AABCS9999M1Z8", "billingStateId": mh,
            "billingPhone": "020 4000 1234",
            "invoiceHeaderText": "Solar power, installed right.",
            "invoiceFooterText": "Thank you for your business. Goods once sold will not be taken back.",
            "bankName": "HDFC Bank", "bankAccountNumber": "50200012345678", "bankIfsc": "HDFC0000123",
            "bankBranch": "Baner, Pune", "upiId": "sunbridge@hdfcbank"}, "billing profile")

    def products(self):
        t = self.tokens["admin"]
        self.ids["product"] = {}
        existing = {p["name"]: p for p in self.must("GET", "/inventory/products?size=200", t).get("content", [])}
        for name, sku, price, tax, stock, hsn, unit in PRODUCTS:
            if name in existing:
                self.ids["product"][name] = existing[name]["id"]
                if stock >= 1000 and existing[name]["stockQuantity"] < 1000:
                    self.must("POST", "/inventory/products/%s/stock-adjustments" % existing[name]["id"], t,
                              {"quantityChange": stock - existing[name]["stockQuantity"],
                               "note": "Demo service capacity"}, name)
                continue
            data = self.must("POST", "/inventory/products", t, {
                "name": name, "sku": sku, "unitPrice": price, "taxRatePercent": tax, "stockQuantity": stock,
                "hsnSacCode": hsn, "unitOfMeasure": unit, "lowStockThreshold": 5,
                "description": name + " (demo catalog item)"}, name)
            self.ids["product"][name] = data["id"]
        print("Created %d products (Battery Backup is low on stock on purpose)" % len(PRODUCTS))

    def employees(self):
        t = self.tokens["admin"]
        self.ids["emp"] = {}
        existing = {e["email"]: e["id"] for e in self.must("GET", "/employees?size=200", t).get("content", [])}
        for key, name, phone, manager, state, designation in EMPLOYEES:
            city, state_id = self.city_in(state)
            body = {"fullName": name, "email": "%s@sunbridge.demo" % key, "phone": phone,
                    "password": DEMO_PASSWORD, "role": "EMPLOYEE",
                    "designationId": self.pick("DESIGNATION", designation), "stateId": state_id, "cityId": city,
                    "assignedProductIds": [self.pick("PRODUCT", "Solar Inverter 5KVA"),
                                           self.pick("PRODUCT", "Solar Panel 550W")]}
            if manager:
                body["managerId"] = self.ids["emp"][manager]
            if body["email"] in existing:
                self.ids["emp"][key] = existing[body["email"]]
            else:
                self.ids["emp"][key] = self.must("POST", "/employees", t, body, name)["id"]
            auth = self.must("POST", "/auth/login", None,
                             {"email": "%s@sunbridge.demo" % key, "password": DEMO_PASSWORD})
            self.tokens[key] = auth["accessToken"]
        print("Created %d employees (Priya manages Rohan, Anjali and Karan)" % len(EMPLOYEES))

    def customers(self):
        t = self.tokens["admin"]
        self.ids["customer"] = []
        existing = {c["name"]: c["id"] for c in self.must("GET", "/customers?size=200", t).get("content", [])}
        for i, (name, contact, phone, email, state, gstin) in enumerate(CUSTOMERS):
            city, state_id = self.city_in(state, i)
            if name in existing:
                self.ids["customer"].append({"id": existing[name], "name": name, "state": state_id, "label": state})
                continue
            body = {"name": name, "contactPerson": contact, "phone": phone, "email": email,
                    "address": "Plot %d, Industrial Area, %s" % (10 + i * 7, state),
                    "stateId": state_id, "cityId": city}
            if gstin:
                body["gstin"] = gstin
            data = self.must("POST", "/customers", t, body, name)
            self.ids["customer"].append({"id": data["id"], "name": name, "state": state_id, "label": state})
        print("Created %d customers (Maharashtra = same state as us, others are interstate)" % len(CUSTOMERS))

    def leads(self):
        self.ids["lead"] = {}
        today = date.today()
        existing = {l["companyName"]: l for l in self.must("GET", "/leads?size=200", self.tokens["admin"]).get("content", [])}
        for i, (company, contact, phone, state, interest, status, rep, industry, source) in enumerate(LEADS):
            if company in existing:
                self.ids["lead"][company] = {"id": existing[company]["id"], "rep": rep,
                                             "status": existing[company]["status"]}
                continue
            city, state_id = self.city_in(state, i)
            body = {"companyName": company, "contactPerson": contact, "contactNo": phone,
                    "email": "%s@lead.demo" % phone, "stateId": state_id, "cityId": city,
                    "industryId": self.pick("INDUSTRY", industry), "leadSourceId": self.pick("LEAD_SOURCE", source),
                    "interestLevelId": self.pick("INTEREST_LEVEL", interest),
                    "designationId": self.pick("DESIGNATION", "Owner" if i % 2 == 0 else "Purchase Manager"),
                    "requirements": "Rooftop solar for ~%d kW connected load, wants payback under 4 years." % (20 + i * 5),
                    "budgetRange": "%d-%d lakh" % (5 + i % 6, 9 + i % 6),
                    "turnover": 5000000 + i * 1750000,
                    "nextFollowupDate": (today + timedelta(days=1 + i % 9)).isoformat(),
                    "expectedCloseDate": (today + timedelta(days=15 + i * 2)).isoformat(),
                    "productIds": [self.pick("PRODUCT", "Solar Inverter 5KVA")]}
            data = self.must("POST", "/leads", self.tokens[rep], body, company)
            lead_id = data["id"]
            self.ids["lead"][company] = {"id": lead_id, "rep": rep, "status": data["status"]}
            if status == "LOST":
                self.must("PATCH", "/leads/%s/status" % lead_id, self.tokens[rep], {
                    "status": "LOST", "lostReasonId": self.pick("LOST_REASON", LOST_REASONS[i % len(LOST_REASONS)])},
                    company)
            elif status:
                self.must("PATCH", "/leads/%s/status" % lead_id, self.tokens[rep], {"status": status}, company)
        print("Created %d leads across %d reps, in a mix of statuses" % (len(LEADS), len(EMPLOYEES)))

    def visits(self):
        today = date.today()
        made = 0
        completed = 0
        for i, (company, info) in enumerate(self.ids["lead"].items()):
            if i % 2:
                continue
            body = {"leadId": info["id"], "visitDate": (today + timedelta(days=i % 5)).isoformat(),
                    "visitType": "FIELD" if i % 4 == 0 else "TELEPHONIC",
                    "scheduledTime": "%02d:30:00" % (10 + i % 6),
                    "purposeId": self.pick("VISIT_PURPOSE", ["Sales Pitch", "Technical Demo", "Site Survey",
                                                             "Follow-up Meeting"][i % 4])}
            data = self.soft("POST", "/visits", self.tokens[info["rep"]], body, "visit " + company)
            if not data:
                continue
            made += 1
            if i % 4 == 0 and body["visitDate"] == today.isoformat():
                if self.soft("PATCH", "/visits/%s/status" % data["id"], self.tokens[info["rep"]],
                             {"status": "COMPLETED"}, "complete visit"):
                    completed += 1
        print("Created %d visits (%d already completed today)" % (made, completed))

    def leave_and_attendance(self):
        t = self.tokens["admin"]
        types = {}
        for name, code, days in (("Casual Leave", "CL", 12), ("Sick Leave", "SL", 8), ("Earned Leave", "EL", 15)):
            data = self.soft("POST", "/leave-types", t,
                             {"name": name, "code": code, "defaultAllocationDays": days}, "leave type " + name)
            if data:
                types[code] = data["id"]
        if not types:
            return
        today = date.today()
        requests = [("rohan", "CL", 7, 8, "Family function"), ("anjali", "SL", 3, 3, "Fever"),
                    ("karan", "EL", 14, 18, "Vacation"), ("vikram", "CL", 10, 10, "Personal work"),
                    ("neha", "SL", 5, 6, "Dental procedure")]
        created = []
        for rep, code, start, end, reason in requests:
            if code not in types:
                continue
            data = self.soft("POST", "/leave-requests", self.tokens[rep], {
                "leaveTypeId": types[code], "startDate": (today + timedelta(days=start)).isoformat(),
                "endDate": (today + timedelta(days=end)).isoformat(), "reason": reason}, "leave request")
            if data:
                created.append(data["id"])
        for i, lr in enumerate(created):  # approve some, leave the rest pending for the demo
            if i % 2 == 0:
                self.soft("PATCH", "/leave-requests/%s/approve" % lr, t, {}, "approve leave")
        clocked = 0
        for rep in ("priya", "rohan", "anjali", "karan", "vikram"):
            if self.soft("POST", "/attendance/clock-in", self.tokens[rep], None, "clock in"):
                clocked += 1
        print("Created %d leave types, %d leave requests (some approved, some pending), %d clock-ins"
              % (len(types), len(created), clocked))

    def has_quotations(self):
        data = self.api.call("GET", "/quotations?size=1", self.tokens["admin"])[1]
        return isinstance(data, dict) and data.get("totalElements", 0) > 0

    def has_direct_invoices(self):
        data = self.api.call("GET", "/invoices?size=200", self.tokens["admin"])[1]
        return isinstance(data, dict) and any(i.get("quotationId") is None for i in data.get("content", []))

    def quotations_step(self):
        t = self.tokens["admin"]
        prod = self.ids["product"]
        today = date.today()
        cust = self.ids["customer"]

        def line(name, qty, disc=None):
            body = {"productId": prod[name], "quantity": qty}
            if disc is not None:
                body["discountPercent"] = disc
            return body

        def quotation(customer, lines, status, rep="admin", lead=None, notes=None):
            body = {"customerId": customer["id"], "quotationDate": today.isoformat(),
                    "validTillDate": (today + timedelta(days=15)).isoformat(),
                    "stateId": customer["state"], "lineItems": lines, "status": status,
                    "quotationNotes": notes or "Prices valid for 15 days. Installation scheduling within 2 weeks of PO.",
                    "termsAndConditions": "50% advance, balance on commissioning. 5-year warranty on panels."}
            if lead:
                body["leadId"] = lead
            return self.must("POST", "/quotations", self.tokens[rep], body, "quotation")

        lead = lambda company: self.ids["lead"][company]["id"]
        q = []
        q.append(quotation(cust[0], [line("Solar Panel 550W", 20), line("Solar Inverter 10KVA", 1),
                                     line("Installation Service", 1)], "DRAFT"))
        q.append(quotation(cust[1], [line("Solar Panel 550W", 40, 5), line("Solar Inverter 10KVA", 2)], "SENT"))
        sent_ids = [quotation(cust[2], [line("Solar Inverter 5KVA", 2), line("Battery Backup", 1)], "SENT"),
                    quotation(cust[3], [line("Solar Panel 330W", 30), line("Solar Inverter 5KVA", 1)], "SENT"),
                    quotation(cust[4], [line("Solar Panel 550W", 12), line("Solar Inverter 5KVA", 1),
                                        line("Annual Maintenance Contract", 1)], "SENT",
                              rep="rohan", lead=lead("Bhoomi Agro Foods")),
                    quotation(cust[5], [line("Solar Inverter 10KVA", 1), line("Installation Service", 1)], "SENT")]
        # [0] approved (not yet invoiced), [1] rejected, [2] Rohan's, tied to a Hot lead -> converted by
        # Rohan (the lead becomes CLOSED_WON), [3] approved and converted by the admin.
        self.soft("PATCH", "/quotations/%s/status" % sent_ids[0]["id"], t, {"status": "APPROVED"}, "approve")
        self.soft("PATCH", "/quotations/%s/status" % sent_ids[1]["id"], t, {"status": "REJECTED"}, "reject")
        self.soft("POST", "/quotations/%s/convert-to-invoice" % sent_ids[2]["id"], self.tokens["rohan"], {}, "convert")
        self.soft("PATCH", "/quotations/%s/status" % sent_ids[3]["id"], t, {"status": "APPROVED"}, "approve")
        self.soft("POST", "/quotations/%s/convert-to-invoice" % sent_ids[3]["id"], t, {}, "convert")

        print("Created 6 quotations (draft/sent/approved/rejected/converted)")

    def invoices_step(self):
        t = self.tokens["admin"]
        prod = self.ids["product"]
        today = date.today()
        cust = self.ids["customer"]

        def line(name, qty, disc=None):
            body = {"productId": prod[name], "quantity": qty}
            if disc is not None:
                body["discountPercent"] = disc
            return body

        def invoice(customer, lines, rep, due_in=15, invoice_days_ago=0):
            body = {"customerName": customer["name"], "customerId": customer["id"],
                    "invoiceDate": (today - timedelta(days=invoice_days_ago)).isoformat(),
                    "dueDate": (today - timedelta(days=invoice_days_ago) + timedelta(days=due_in)).isoformat(),
                    "placeOfSupplyStateId": customer["state"], "lineItems": lines}
            return self.soft("POST", "/invoices", self.tokens[rep], body, "invoice " + customer["name"])

        inv_full = invoice(cust[0], [line("Solar Panel 330W", 10), line("Installation Service", 1)], "admin",
                           invoice_days_ago=20)
        inv_part = invoice(cust[1], [line("Solar Inverter 5KVA", 1), line("Solar Panel 550W", 8)], "admin",
                           invoice_days_ago=10)
        inv_overdue = invoice(cust[6], [line("Solar Panel 550W", 6)], "admin", due_in=15, invoice_days_ago=40)
        inv_unpaid = invoice(cust[7], [line("Solar Inverter 3KVA", 3)], "admin")
        inv_rep = invoice(cust[4], [line("Annual Maintenance Contract", 1)], "rohan", invoice_days_ago=5)
        inv_cancel = invoice(cust[3], [line("Solar Panel 330W", 2)], "admin")
        paid = 0
        if inv_full:
            self.soft("POST", "/invoices/%s/payments" % inv_full["id"], t,
                      {"amount": str(inv_full["grandTotal"]), "mode": "BANK_TRANSFER",
                       "reference": "UTR 4021998877"}, "pay")
            paid += 1
        if inv_part:
            self.soft("POST", "/invoices/%s/payments" % inv_part["id"], t,
                      {"amount": "25000.00", "mode": "UPI", "reference": "upi-8841"}, "part pay")
            paid += 1
        if inv_rep:
            self.soft("POST", "/invoices/%s/payments" % inv_rep["id"], self.tokens["rohan"],
                      {"amount": str(inv_rep["grandTotal"]), "mode": "CASH"}, "pay")
            paid += 1
        if inv_cancel:
            self.soft("POST", "/invoices/%s/cancel" % inv_cancel["id"], t,
                      {"reason": "Customer changed the order"}, "cancel")
        print("Created 6 direct invoices (%d with payments, 1 overdue, 1 cancelled)" % paid)

    def summary(self):
        print("\nDone. Log in at your frontend (or via POST /auth/login) with:")
        print("  Admin     %-26s password: %s" % (ADMIN_EMAIL, DEMO_PASSWORD))
        for key, name, *_ in EMPLOYEES:
            print("  Employee  %-26s password: %s   (%s)" % ("%s@sunbridge.demo" % key, DEMO_PASSWORD, name))
        if self.warnings:
            print("\nSome optional steps were skipped (the rest of the data is fine):")
            for w in self.warnings:
                print("  - " + w)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8080/api/v1")
    parser.add_argument("--platform-key", default="dev-only-platform-key-change-me")
    args = parser.parse_args()

    seeder = Seeder(Api(args.base_url, args.platform_key))
    seeder.bootstrap_org()
    seeder.grant_entitlements()
    seeder.billing_profile()
    seeder.products()
    seeder.employees()
    seeder.customers()
    seeder.leads()
    started_billing = seeder.has_quotations()  # visits/leave run before billing, so they are done
    if not started_billing:
        seeder.visits()
        seeder.leave_and_attendance()
    if started_billing and seeder.has_direct_invoices():
        sys.exit("Demo organization already has data - nothing more added (re-run safe).")
    if not started_billing:
        seeder.quotations_step()
    if not seeder.has_direct_invoices():
        seeder.invoices_step()
    seeder.summary()


if __name__ == "__main__":
    main()
