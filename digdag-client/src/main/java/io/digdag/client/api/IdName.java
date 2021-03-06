package io.digdag.client.api;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.immutables.value.Value;

@Value.Immutable
@JsonSerialize(as = ImmutableIdName.class)
@JsonDeserialize(as = ImmutableIdName.class)
public abstract class IdName
{
    public abstract int getId();

    public abstract String getName();

    public static IdName of(int id, String name)
    {
        return ImmutableIdName.builder()
            .id(id)
            .name(name)
            .build();
    }
}
