package com.salesmanager.crm.customer;

import com.salesmanager.crm.customer.dto.CustomerCreateRequest;
import com.salesmanager.crm.customer.dto.CustomerFilter;
import com.salesmanager.crm.customer.dto.CustomerResponse;
import com.salesmanager.crm.customer.dto.CustomerUpdateRequest;
import com.salesmanager.crm.entitlement.FeatureEntitlement;
import com.salesmanager.crm.entitlement.RequireEntitlement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read/create/update all open to any entitled authenticated employee, no ADMIN restriction -
 * a rep quick-adding a customer mid-quotation must not be blocked on Admin involvement. No
 * delete endpoint in v1 - deactivate via the `active` flag on update instead, same as Product.
 */
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public Page<CustomerResponse> list(@RequestParam(required = false) String search,
                                        @RequestParam(required = false) UUID cityId,
                                        @RequestParam(required = false) UUID stateId,
                                        @RequestParam(defaultValue = "false") boolean includeInactive,
                                        Pageable pageable) {
        CustomerFilter filter = new CustomerFilter(search, cityId, stateId, includeInactive);
        return customerService.list(filter, pageable).map(CustomerResponse::from);
    }

    @GetMapping("/{id}")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public CustomerResponse getById(@PathVariable UUID id) {
        return CustomerResponse.from(customerService.getById(id));
    }

    @PostMapping
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse create(@Valid @RequestBody CustomerCreateRequest request) {
        return CustomerResponse.from(customerService.create(request));
    }

    @PutMapping("/{id}")
    @RequireEntitlement(FeatureEntitlement.INVOICING)
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody CustomerUpdateRequest request) {
        return CustomerResponse.from(customerService.update(id, request));
    }
}
