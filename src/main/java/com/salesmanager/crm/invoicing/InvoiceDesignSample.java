package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.invoicing.dto.BillingProfileResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** An in-memory, never-persisted invoice used by the design preview when no real invoice is
 * chosen - seller block taken from the company's real billing profile, everything else made up. */
final class InvoiceDesignSample {

    private InvoiceDesignSample() {
    }

    static Invoice invoice(BillingProfileResponse seller) {
        Invoice invoice = Invoice.builder()
                .invoiceNumber("INV/26-27/0001")
                .customerName("Sample Customer Pvt Ltd")
                .customerContactPerson("Asha Verma")
                .customerPhone("9810000000")
                .customerEmail("accounts@sample-customer.example")
                .customerAddress("21, Industrial Area, Sample City, 400001")
                .customerGstin("27ABCDE1234F1Z5")
                .invoiceDate(LocalDate.now())
                .dueDate(LocalDate.now().plusDays(15))
                .placeOfSupply("Maharashtra")
                .subtotal(new BigDecimal("30000.00"))
                .taxableAmount(new BigDecimal("30000.00"))
                .cgstTotal(new BigDecimal("2700.00"))
                .sgstTotal(new BigDecimal("2700.00"))
                .igstTotal(BigDecimal.ZERO)
                .taxTotal(new BigDecimal("5400.00"))
                .grandTotal(new BigDecimal("35400.00"))
                .notes("Thank you for your business.")
                .build();
        invoice.setSellerName(seller.businessName());
        invoice.setSellerGstin(seller.billingGstin());
        invoice.setSellerAddress(seller.billingAddress());
        invoice.setSellerPhone(seller.billingPhone());
        invoice.setSellerBankName(seller.bankName());
        invoice.setSellerBankAccountNumber(seller.bankAccountNumber());
        invoice.setSellerBankIfsc(seller.bankIfsc());
        invoice.setSellerBankBranch(seller.bankBranch());
        invoice.setSellerUpiId(seller.upiId());
        invoice.setSellerHeaderText(seller.invoiceHeaderText());
        invoice.setSellerFooterText(seller.invoiceFooterText());
        return invoice;
    }

    static List<InvoiceLineItem> lines() {
        return List.of(
                line("Solar Inverter 3KVA", "8504", "2", "10000.00", "20000.00", "1800.00"),
                line("Installation Service", "9954", "1", "10000.00", "10000.00", "900.00"));
    }

    private static InvoiceLineItem line(String description, String hsn, String qty, String unitPrice,
                                         String subtotal, String halfTax) {
        return InvoiceLineItem.builder()
                .description(description)
                .hsnSac(hsn)
                .unit("Nos")
                .quantity(new BigDecimal(qty))
                .unitPrice(new BigDecimal(unitPrice))
                .taxRatePercent(new BigDecimal("18"))
                .lineSubtotal(new BigDecimal(subtotal))
                .lineTaxAmount(new BigDecimal(halfTax).multiply(BigDecimal.valueOf(2)))
                .lineCgstAmount(new BigDecimal(halfTax))
                .lineSgstAmount(new BigDecimal(halfTax))
                .build();
    }
}
