/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.data.schema.compatibility;


import com.linkedin.data.message.MessageList;
import com.linkedin.data.schema.ArrayDataSchema;
import com.linkedin.data.schema.DataSchema;
import com.linkedin.data.schema.DataSchemaConstants;
import com.linkedin.data.schema.EnumDataSchema;
import com.linkedin.data.schema.FixedDataSchema;
import com.linkedin.data.schema.MapDataSchema;
import com.linkedin.data.schema.NamedDataSchema;
import com.linkedin.data.schema.PrimitiveDataSchema;
import com.linkedin.data.schema.RecordDataSchema;
import com.linkedin.data.schema.TyperefDataSchema;
import com.linkedin.data.schema.UnionDataSchema;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

/**
 * Compare two {@link com.linkedin.data.schema.DataSchema} for compatibility.
 */
public class CompatibilityChecker
{
  public static CompatibilityResult checkCompatibility(DataSchema older, DataSchema newer, CompatibilityOptions options)
  {
    CompatibilityChecker checker = new CompatibilityChecker();
    checker.run(older, newer, options);
    return checker._result;
  }

  private static class Checked
  {
    private Checked(DataSchema older, DataSchema newer)
    {
      _older = older;
      _newer = newer;
    }

    @Override
    public boolean equals(Object o)
    {
      if (o == null)
        return false;
      Checked other = (Checked) o;
      return (other._older == _older && other._newer == _newer);
    }

    @Override
    public int hashCode()
    {
      return _older.hashCode() + _newer.hashCode();
    }

    private final DataSchema _older;
    private final DataSchema _newer;
  }

  private final ArrayDeque<String> _path = new ArrayDeque<String>();
  private final HashSet<Checked> _checked = new HashSet<Checked>();
  private Result _result;
  private CompatibilityOptions _options;

  private CompatibilityChecker()
  {
  }

  private CompatibilityResult run(DataSchema older, DataSchema newer, CompatibilityOptions options)
  {
    _path.clear();
    _checked.clear();
    _options = options;
    _result = new Result();
    check(older, newer);
    return _result;
  }

  private void check(DataSchema older, DataSchema newer)
  {
    Checked toCheck = new Checked(older, newer);
    if (_checked.contains(toCheck))
    {
      return;
    }
    _checked.add(toCheck);

    if (older == newer)
    {
      return;
    }

    int pathCount = 1;
    if (_options.getMode() == CompatibilityOptions.Mode.DATA)
    {
      older = older.getDereferencedDataSchema();
      while (newer.getType() == DataSchema.Type.TYPEREF)
      {
        TyperefDataSchema typerefDataSchema = ((TyperefDataSchema) newer);
        _path.addLast(typerefDataSchema.getFullName());
        _path.addLast(DataSchemaConstants.REF_KEY);
        pathCount++;
        newer = typerefDataSchema.getRef();
      }
    }
    if (newer.getType() == DataSchema.Type.TYPEREF)
    {
      _path.addLast(((TyperefDataSchema) newer).getFullName());
    }
    else
    {
      _path.addLast(newer.getUnionMemberKey());
    }

    switch (newer.getType())
    {
      case TYPEREF:
        if (isSameType(older, newer))
         checkTyperef((TyperefDataSchema) older, (TyperefDataSchema) newer);
        break;
      case RECORD:
        if (isSameType(older, newer))
          checkRecord((RecordDataSchema) older, (RecordDataSchema) newer);
        break;
      case ARRAY:
        if (isSameType(older, newer))
          checkArray((ArrayDataSchema) older, (ArrayDataSchema) newer);
        break;
      case MAP:
        if (isSameType(older, newer))
          checkMap((MapDataSchema) older, (MapDataSchema) newer);
        break;
      case ENUM:
        if (isSameType(older, newer))
          checkEnum((EnumDataSchema) older, (EnumDataSchema) newer);
        break;
      case FIXED:
        if (isSameType(older, newer))
          checkFixed((FixedDataSchema) older, (FixedDataSchema) newer);
        break;
      case UNION:
        if (isSameType(older, newer))
          checkUnion((UnionDataSchema) older, (UnionDataSchema) newer);
        break;
      default:
        if (newer instanceof PrimitiveDataSchema)
          checkPrimitive(older, newer);
        else
          throw new IllegalStateException("Unknown schema type " + newer.getType() +
                                          ", checking old schema " + older +
                                          ", new schema " + newer);
        break;
    }

    for (;pathCount > 0; pathCount--)
    {
      _path.removeLast();
    }
    return;
  }

  private void appendTypeChangedMessage(DataSchema.Type olderType, DataSchema.Type newerType)
  {
    appendMessage(CompatibilityMessage.Impact.BREAKS_NEW_AND_OLD_READERS,
                  "schema type changed from %s to %s",
                  olderType.toString().toLowerCase(),
                  newerType.toString().toLowerCase());
  }

  private boolean isSameType(DataSchema.Type olderType, DataSchema.Type newerType)
  {
    boolean isSameType = (olderType == newerType);
    if (isSameType == false)
    {
      appendTypeChangedMessage(olderType, newerType);
    }
    return isSameType;
  }

  private boolean isSameType(DataSchema older, DataSchema newer)
  {
    DataSchema.Type olderType = older.getType();
    DataSchema.Type newerType = newer.getType();
    return isSameType(olderType, newerType);
  }

  private void checkPrimitive(DataSchema older, DataSchema newer)
  {
    DataSchema.Type newerType = newer.getType();
    switch (newerType)
    {
      case LONG:
        checkAllowedOlderTypes(older.getType(), newerType, DataSchema.Type.INT);
        break;
      case FLOAT:
        checkAllowedOlderTypes(older.getType(), newerType, DataSchema.Type.INT, DataSchema.Type.LONG);
        break;
      case DOUBLE:
        checkAllowedOlderTypes(older.getType(), newerType,
                               DataSchema.Type.INT,
                               DataSchema.Type.LONG,
                               DataSchema.Type.FLOAT);
        break;
      default:
        isSameType(older, newer);
        break;
    }
  }

  private void checkAllowedOlderTypes(DataSchema.Type olderType,
                                      DataSchema.Type newerType,
                                      DataSchema.Type... allowedOlderTypes)
  {
    if (_options.isAllowPromotions())
    {
      if (olderType != newerType)
      {
        boolean allowed = false;
        for (DataSchema.Type type : allowedOlderTypes)
        {
          if (type == olderType)
          {
            allowed = true;
            break;
          }
        }
        if (allowed)
        {
          appendMessage(CompatibilityMessage.Impact.VALUES_MAY_BE_TRUNCATED_OR_OVERFLOW,
                        "numeric type promoted from %s to %s",
                        olderType.toString().toLowerCase(),
                        newerType.toString().toLowerCase());
        }
        else
        {
          appendTypeChangedMessage(olderType, newerType);
        }
      }
    }
    else
    {
      isSameType(olderType, newerType);
    }
  }

  private void checkRecord(RecordDataSchema older, RecordDataSchema newer)
  {
    checkName(older, newer);

    List<RecordDataSchema.Field> commonFields = new ArrayList<RecordDataSchema.Field>(newer.getFields().size());
    List<String> newerRequiredAdded = new CheckerArrayList<String>();
    List<String> newerOptionalAdded = new CheckerArrayList<String>();
    List<String> requiredToOptional = new CheckerArrayList<String>();
    List<String> optionalToRequired = new CheckerArrayList<String>();
    List<String> newerRequiredRemoved = new CheckerArrayList<String>();
    List<String> newerOptionalRemoved = new CheckerArrayList<String>();

    for (RecordDataSchema.Field newerField : newer.getFields())
    {
      String fieldName = newerField.getName();
      RecordDataSchema.Field olderField = older.getField(fieldName);
      if (olderField == null)
      {
        (newerField.getOptional() ? newerOptionalAdded : newerRequiredAdded).add(fieldName);
      }
      else
      {
        commonFields.add(newerField);
        boolean newerFieldOptional = newerField.getOptional();
        if (newerFieldOptional != olderField.getOptional())
        {
          (newerFieldOptional ? requiredToOptional : optionalToRequired).add(fieldName);
        }
      }
    }
    for (RecordDataSchema.Field olderField : older.getFields())
    {
      String fieldName = olderField.getName();
      RecordDataSchema.Field newerField = newer.getField(fieldName);
      if (newerField == null)
      {
        (olderField.getOptional() ? newerOptionalRemoved : newerRequiredRemoved).add(fieldName);
      }
    }

    if (newerRequiredAdded.isEmpty() == false)
    {
      appendMessage(CompatibilityMessage.Impact.BREAKS_NEW_READER,
                    "new record added required fields %s",
                    newerRequiredAdded);
    }

    if (newerRequiredRemoved.isEmpty() == false)
    {
      appendMessage(CompatibilityMessage.Impact.BREAKS_OLD_READER,
                    "new record removed required fields %s",
                    newerRequiredRemoved);
    }

    if (optionalToRequired.isEmpty() == false)
    {
      appendMessage(CompatibilityMessage.Impact.BREAKS_NEW_READER,
                    "new record changed optional fields to required fields %s",
                    optionalToRequired);
    }

    if (requiredToOptional.isEmpty() == false)
    {
      appendMessage(CompatibilityMessage.Impact.BREAKS_OLD_READER,
                    "new record changed required fields to optional fields %s",
                    requiredToOptional);
    }

    if (newerOptionalAdded.isEmpty() == false)
    {
      appendMessage(CompatibilityMessage.Impact.OLD_READER_IGNORES_DATA,
                    "new record added optional fields %s",
                    newerOptionalAdded);
    }

    if (newerOptionalRemoved.isEmpty() == false)
    {
      appendMessage(CompatibilityMessage.Impact.NEW_READER_IGNORES_DATA,
                    "new record removed optional fields %s",
                    newerOptionalRemoved);
    }

    for (RecordDataSchema.Field newerField : commonFields)
    {
      String fieldName = newerField.getName();

      _path.addLast(fieldName);

      RecordDataSchema.Field olderField = older.getField(fieldName);
      assert(olderField != null);
      check(olderField.getType(), newerField.getType());

      _path.removeLast();
    }
  }

  private void computeAddedUnionMembers(UnionDataSchema base, UnionDataSchema changed,
                                        List<String> added, List<DataSchema> commonMembers)
  {
    for (DataSchema member : changed.getTypes())
    {
      String unionMemberKey = member.getUnionMemberKey();
      if (base.contains(unionMemberKey) == false)
      {
        added.add(unionMemberKey);
      }
      else if (commonMembers != null)
      {
        commonMembers.add(member);
      }
    }
  }

  private void checkUnion(UnionDataSchema older, UnionDataSchema newer)
  {
    // using list to preserve union member order
    List<DataSchema> commonMembers = new CheckerArrayList<DataSchema>(newer.getTypes().size());
    List<String> newerAdded = new CheckerArrayList<String>();
    List<String> olderAdded = new CheckerArrayList<String>();

    computeAddedUnionMembers(older, newer, newerAdded, commonMembers);
    computeAddedUnionMembers(newer, older, olderAdded, null);

    if (newerAdded.isEmpty() == false)
    {
      appendMessage(CompatibilityMessage.Impact.BREAKS_OLD_READER,
                    "new union added members %s",
                    newerAdded);
    }

    if (olderAdded.isEmpty() == false)
    {
      appendMessage(CompatibilityMessage.Impact.BREAKS_NEW_READER,
                    "new union removed members %s",
                    olderAdded);
    }

    for (DataSchema newerSchema : commonMembers)
    {
      String memberKey = newerSchema.getUnionMemberKey();

      DataSchema olderSchema = older.getType(memberKey);
      assert(olderSchema != null);
      check(olderSchema, newerSchema);
    }
  }

  private void checkEnum(EnumDataSchema older, EnumDataSchema newer)
  {
    checkName(older, newer);

    _path.addLast(DataSchemaConstants.SYMBOLS_KEY);

    // using list to preserve symbol order
    List<String> newerOnlySymbols = new CheckerArrayList<String>(newer.getSymbols());
    newerOnlySymbols.removeAll(older.getSymbols());

    List<String> olderOnlySymbols = new CheckerArrayList<String>(older.getSymbols());
    olderOnlySymbols.removeAll(newer.getSymbols());

    if (newerOnlySymbols.isEmpty() == false)
    {
      appendMessage(CompatibilityMessage.Impact.BREAKS_OLD_READER,
                    "new enum added symbols %s",
                    newerOnlySymbols);
    }

    if (olderOnlySymbols.isEmpty() == false)
    {
      appendMessage(CompatibilityMessage.Impact.BREAKS_NEW_READER,
                    "new enum removed symbols %s",
                    olderOnlySymbols);
    }

    _path.removeLast();
  }

  private void checkFixed(FixedDataSchema older, FixedDataSchema newer)
  {
    checkName(older, newer);

    _path.addLast(DataSchemaConstants.SIZE_KEY);

    int olderSize = older.getSize();
    int newerSize = newer.getSize();
    if (olderSize != newerSize)
    {
      appendMessage(CompatibilityMessage.Impact.BREAKS_NEW_AND_OLD_READERS,
                    "fixed size changed from %d to %d",
                    olderSize,
                    newerSize);
    }

    _path.removeLast();
  }

  private void checkTyperef(TyperefDataSchema older, TyperefDataSchema newer)
  {
    checkName(older, newer);
    _path.addLast(DataSchemaConstants.REF_KEY);
    check(older.getDereferencedDataSchema(), newer.getDereferencedDataSchema());
    _path.removeLast();
  }

  private void checkArray(ArrayDataSchema older, ArrayDataSchema newer)
  {
    _path.addLast(DataSchemaConstants.ITEMS_KEY);
    check(older.getItems(), newer.getItems());
    _path.removeLast();
  }

  private void checkMap(MapDataSchema older, MapDataSchema newer)
  {
    _path.addLast(DataSchemaConstants.VALUES_KEY);
    check(older.getValues(), newer.getValues());
    _path.removeLast();
  }

  private void checkName(NamedDataSchema older, NamedDataSchema newer)
  {
    if (_options.isCheckNames() && older.getFullName().equals(newer.getFullName()) == false)
    {
      appendMessage(CompatibilityMessage.Impact.BREAKS_NEW_AND_OLD_READERS,
                    "name changed from %s to %s", older.getFullName(), newer.getFullName());
    }
  }

  private void appendMessage(CompatibilityMessage.Impact impact, String format, Object... args)
  {
    CompatibilityMessage message = new CompatibilityMessage(_path.toArray(), impact, format, args);
    _result._messages.add(message);
  }

  private static class Result implements CompatibilityResult
  {
    private Result()
    {
      _messages = new MessageList<CompatibilityMessage>();
    }

    @Override
    public Collection<CompatibilityMessage> getMessages()
    {
      return _messages;
    }

    @Override
    public boolean isError()
    {
      return _messages.isError();
    }

    private final MessageList<CompatibilityMessage> _messages;
  }

  /**
   * Override {@link #toString()} to print list without square brackets.
   *
   * @param <T> element type of list.
   */
  private static class CheckerArrayList<T> extends ArrayList<T>
  {
    private static final long serialVersionUID = 1L;

    private CheckerArrayList()
    {
      super();
    }

    private CheckerArrayList(int reserve)
    {
      super(reserve);
    }

    private CheckerArrayList(Collection<T> c)
    {
      super(c);
    }

    @Override
    public String toString()
    {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < size(); i++)
      {
        if (i != 0)
          sb.append(", ");
        sb.append(get(i));
      }
      return sb.toString();
    }
  }
}
