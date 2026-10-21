package com.salesmanager.crm.invoicing;

import com.salesmanager.crm.common.NotFoundException;
import com.salesmanager.crm.invoicing.dto.BillingProfileResponse;
import com.salesmanager.crm.invoicing.dto.BillingProfileUpdateRequest;
import com.salesmanager.crm.masterdata.MasterDataService;
import com.salesmanager.crm.masterdata.MasterType;
import com.salesmanager.crm.security.CurrentUser;
import com.salesmanager.crm.tenant.Organization;
import com.salesmanager.crm.tenant.OrganizationRepository;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The seller header shown on a generated invoice PDF - mirrors theme.ThemeService's layering
 * (GET open to any authenticated user, PUT ADMIN-only via the controller), simpler here since
 * every field is a plain nullable column, not JSON needing parse/merge.
 *
 * <p>The logo is stored directly as bytea on Organization (see V14 migration comment) rather
 * than S3 - only PNG/JPEG, capped at {@link #MAX_LOGO_BYTES}, enforced here since neither is a
 * DB constraint.
 */
@Service
public class BillingProfileService {

    private static final long MAX_LOGO_BYTES = 2L * 1024 * 1024;

    private final OrganizationRepository organizationRepository;
    private final CurrentUser currentUser;
    private final MasterDataService masterDataService;

    public BillingProfileService(OrganizationRepository organizationRepository, CurrentUser currentUser,
                                  MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
        this.organizationRepository = organizationRepository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public BillingProfileResponse getBillingProfile() {
        return BillingProfileResponse.from(loadCurrentOrganization());
    }

    @Transactional(noRollbackFor = com.salesmanager.crm.masterdata.InvalidReferenceException.class)
    public BillingProfileResponse updateBillingProfile(BillingProfileUpdateRequest request) {
        masterDataService.validateReference(request.billingStateId(), MasterType.STATE, "billingStateId");
        Organization organization = loadCurrentOrganization();
        organization.setBillingStateId(request.billingStateId());
        organization.setBillingAddress(request.billingAddress());
        organization.setBillingGstin(request.billingGstin());
        organization.setBillingPhone(request.billingPhone());
        organization.setInvoiceHeaderText(request.invoiceHeaderText());
        organization.setInvoiceFooterText(request.invoiceFooterText());
        organization.setBankName(request.bankName());
        organization.setBankAccountNumber(request.bankAccountNumber());
        organization.setBankIfsc(request.bankIfsc());
        organization.setBankBranch(request.bankBranch());
        organization.setUpiId(request.upiId());
        Organization saved = organizationRepository.saveAndFlush(organization);
        return BillingProfileResponse.from(saved);
    }

    @Transactional(noRollbackFor = InvalidLogoException.class)
    public BillingProfileResponse uploadLogo(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidLogoException("file", "Logo file must not be empty");
        }
        if (file.getSize() > MAX_LOGO_BYTES) {
            throw new InvalidLogoException("file", "Logo file must be at most 2MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new InvalidLogoException("file", "Failed to read uploaded logo file");
        }

        // The browser-declared Content-Type on a multipart upload is derived from the file's
        // NAME/extension, not its actual bytes - a WebP (or any other format) file that happens
        // to be named "logo.png" is reported as image/png and would sail through a content-type-
        // only check. openhtmltopdf/PDFBox has no WebP decoder (unlike a browser's <img> tag,
        // which renders WebP natively regardless of declared type) - a mismatched real format
        // silently fails at PDF-render time ("Can't read image file"), long after upload
        // succeeded, which is exactly the bug this sniff-the-real-bytes check closes. The
        // sniffed type - not the client's declared one - is what gets stored and served.
        String actualContentType = detectImageContentType(bytes);
        if (actualContentType == null) {
            throw new InvalidLogoException("file",
                    "Logo must be a genuine PNG or JPEG image (this file's content doesn't match either "
                            + "format, even if its name suggests one - a WebP or other format saved with a "
                            + ".png/.jpg extension is a common cause)");
        }

        Organization organization = loadCurrentOrganization();
        organization.setLogoImage(bytes);
        organization.setLogoContentType(actualContentType);
        Organization saved = organizationRepository.saveAndFlush(organization);
        return BillingProfileResponse.from(saved);
    }

    /** Sniffs the real format from magic bytes rather than trusting any client-declared
     * Content-Type - see uploadLogo's javadoc comment for why this matters. Returns null for
     * anything that isn't a genuine PNG or JPEG. */
    private static String detectImageContentType(byte[] bytes) {
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return "image/png";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        return null;
    }

    @Transactional
    public BillingProfileResponse deleteLogo() {
        Organization organization = loadCurrentOrganization();
        organization.setLogoImage(null);
        organization.setLogoContentType(null);
        Organization saved = organizationRepository.saveAndFlush(organization);
        return BillingProfileResponse.from(saved);
    }

    /** For the GET /organizations/me/logo preview endpoint - empty if no logo is set. */
    @Transactional(readOnly = true)
    public Optional<LogoBytes> getLogoBytes() {
        Organization organization = loadCurrentOrganization();
        if (organization.getLogoImage() == null) {
            return Optional.empty();
        }
        return Optional.of(new LogoBytes(organization.getLogoImage(), organization.getLogoContentType()));
    }

    /** For InvoicePdfHtmlBuilder - openhtmltopdf renders a data URI inline with no extra
     * network fetch, so the PDF stays self-contained and fast to render. */
    @Transactional(readOnly = true)
    public Optional<String> getLogoDataUri() {
        return getLogoBytes().map(logo ->
                "data:" + logo.contentType() + ";base64," + Base64.getEncoder().encodeToString(logo.bytes()));
    }

    private Organization loadCurrentOrganization() {
        UUID organizationId = currentUser.get().getOrganizationId();
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new NotFoundException("Organization not found: " + organizationId));
    }

    public record LogoBytes(byte[] bytes, String contentType) {
    }
}
