package org.hibernate.examples.mapping.associations.join;

import org.hibernate.examples.model.AbstractValueObject;
import org.hibernate.examples.utils.HashTool;
import org.hibernate.examples.utils.ToStringHelper;

import javax.persistence.Embeddable;

/**
 * org.hibernate.examples.mapping.associations.join.JoinAddress
 *
 * @author 배성혁 sunghyouk.bae@gmail.com
 * @since 2013. 11. 28. 오후 11:12
 */
@Embeddable
public class JoinAddress extends AbstractValueObject {

    private String street;
    private String city;
    private String zipcode;

    @Override
    public int hashCode() {
        return HashTool.compute(street, city, zipcode);
    }

    @Override
    public ToStringHelper buildStringHelper() {
        return super.buildStringHelper()
                    .add("street", street)
                    .add("city", city)
                    .add("zipcode", zipcode);
    }

    private static final long serialVersionUID = -6565835937195482591L;

    public String getStreet() {
        return this.street;
    }

    public String getCity() {
        return this.city;
    }

    public String getZipcode() {
        return this.zipcode;
    }

    public void setStreet(String street) {
        this.street = street;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public void setZipcode(String zipcode) {
        this.zipcode = zipcode;
    }
}

