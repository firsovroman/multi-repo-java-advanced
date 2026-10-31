package com.example.person.config;

import com.example.person.api.model.IndividualRequest;
import com.example.person.api.model.UpdateAddressRequest;
import com.example.person.api.model.UpdateUserRequest;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import org.springframework.boot.jackson.JacksonMixin;

/**
 * В PATCH все поля необязательны, поэтому в сгенерированной модели отсутствующее поле и явный {@code null}
 * неотличимы. Обязательные поля отвергают {@code null} ещё при разборе тела, и он превращается в 400.
 */
final class RequiredFieldsJackson {

    private RequiredFieldsJackson() {
    }

    @JacksonMixin(UpdateUserRequest.class)
    abstract static class UpdateUserMixin {

        @JsonSetter(nulls = Nulls.FAIL)
        abstract void setEmail(String email);

        @JsonSetter(nulls = Nulls.FAIL)
        abstract void setFirstName(String firstName);

        @JsonSetter(nulls = Nulls.FAIL)
        abstract void setLastName(String lastName);

        @JsonSetter(nulls = Nulls.FAIL)
        abstract void setAddress(UpdateAddressRequest address);

        @JsonSetter(nulls = Nulls.FAIL)
        abstract void setIndividual(IndividualRequest individual);
    }

    @JacksonMixin(UpdateAddressRequest.class)
    abstract static class UpdateAddressMixin {

        @JsonSetter(nulls = Nulls.FAIL)
        abstract void setCountryAlpha3(String countryAlpha3);

        @JsonSetter(nulls = Nulls.FAIL)
        abstract void setCountryAlpha2(String countryAlpha2);

        @JsonSetter(nulls = Nulls.FAIL)
        abstract void setCity(String city);

        @JsonSetter(nulls = Nulls.FAIL)
        abstract void setAddressLine(String addressLine);
    }
}
