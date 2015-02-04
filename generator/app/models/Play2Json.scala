package models

import lib.Text._
import generator.{ScalaDatatype, ScalaModel, ScalaPrimitive, ScalaUnion}

case class Play2Json(
  serviceName: String
) {

  private case class ReadWrite(name: String)
  private val Reads = ReadWrite("Reads")
  private val Writes = ReadWrite("Writes")

  def generate(model: ScalaModel): String = {
    model.unions match {
      case Nil => readers(model) + "\n\n" + writers(model)
      case unions => readers(model)
    }
  }

  def generate(union: ScalaUnion): String = {
    readers(union) + "\n\n" + writers(union)
  }

  def readers(union: ScalaUnion): String = {
    Seq(
      s"${identifier(union.name, Reads)} = {",
      s"  (",
      union.typesForJson.map { jsonType =>
        s"""(__ \\ "${jsonType.shortName}").read[${jsonType.className}].asInstanceOf[play.api.libs.json.Reads[${union.name}]]"""
      }.mkString("\norElse\n").indent(4),
      s"  )",
      s"}"
    ).mkString("\n")
  }

  def writers(union: ScalaUnion): String = {
    Seq(
      s"${identifier(union.name, Writes)} = new play.api.libs.json.Writes[${union.name}] {",
      s"  def writes(obj: ${union.name}): play.api.libs.json.JsObject = {",
      s"    obj match {",
      union.typesForJson.map { t =>
        t.primitive match {
          case ScalaPrimitive.Unit | ScalaPrimitive.Union(_, _) => {
            sys.error(s"Invalid type[$t] for union")
          }
          case ScalaPrimitive.String | ScalaPrimitive.Integer | ScalaPrimitive.Double | ScalaPrimitive.Long | ScalaPrimitive.Boolean => {
            sys.error("TODO: Add primitive support to unions")
          }
          case ScalaPrimitive.DateIso8601 | ScalaPrimitive.DateTimeIso8601 | ScalaPrimitive.Uuid | ScalaPrimitive.Decimal | ScalaPrimitive.Object | ScalaPrimitive.Enum(_, _)=> {
            s"""case x: ${t.className} => play.api.libs.json.Json.obj("${t.shortName}" -> x)"""
          }
          case ScalaPrimitive.Model(ns, name) => {
            Seq(
              s"case x: ${t.className} => {",
              s"  play.api.libs.json.Json.obj(",
              s"""    "${t.shortName}" -> play.api.libs.json.Json.obj(""",
              Seq("guid", "email").map( f => s""""$f" -> x.$f""" ).mkString(",\n").indent(6),
              "    )",
              "  )",
              "}"
            ).mkString("\n")
          }
        }

      }.mkString("\n").indent(6),
      "    }",
      "  }",
      "}"
    ).mkString("\n")
  }

  private[models] def readers(model: ScalaModel): String = {
    Seq(
      s"${privateIdentifier(model)}${identifier(model.name, Reads)} = {",
      fieldReaders(model).indent(2),
      s"}"
    ).mkString("\n")
  }

  private[models] def fieldReaders(model: ScalaModel): String = {
    val serializations = model.fields.map { field =>
      field.datatype match {
        case ScalaDatatype.List(types) => {
          val nilValue = field.datatype.nilValue
          s"""(__ \\ "${field.originalName}").readNullable[${field.datatype.name}].map(_.getOrElse($nilValue))"""
        }
        case ScalaDatatype.Map(types) => {
          val nilValue = field.datatype.nilValue
          s"""(__ \\ "${field.originalName}").readNullable[${field.datatype.name}].map(_.getOrElse($nilValue))"""
        }
        case ScalaDatatype.Option(types) => {
            s"""(__ \\ "${field.originalName}").readNullable[${field.datatype.name}]"""
        }
        case ScalaDatatype.Singleton(types) => {
          if (field.isOption) {
            s"""(__ \\ "${field.originalName}").readNullable[${field.datatype.name}]"""
          } else {
            s"""(__ \\ "${field.originalName}").read[${field.datatype.name}]"""
          }
        }
      }
    }

    model.fields match {
      case field :: Nil => {
        serializations.head + s""".map { x => new ${model.name}(${field.name} = x) }"""
      }
      case fields => {
        Seq(
          "(",
          serializations.mkString(" and\n").indent(2),
          s")(${model.name}.apply _)"
        ).mkString("\n")
      }
    }
  }

  private[models] def writers(model: ScalaModel): String = {
    assert(model.unions.isEmpty, s"Model[${model.name}] is part of a union type - writes is part of union type writer")
    model.fields match {
      case field :: Nil => {
        Seq(
          s"${privateIdentifier(model)}${identifier(model.name, Writes)} = new play.api.libs.json.Writes[${model.name}] {",
          s"  def writes(x: ${model.name}) = play.api.libs.json.Json.obj(",
          s"""    "${field.originalName}" -> play.api.libs.json.Json.toJson(x.${field.name})""",
          "  )",
          "}"
        ).mkString("\n")
      }

      case fields => {
        Seq(
          s"${privateIdentifier(model)}${identifier(model.name, Writes)} = {",
          s"  (",
          model.fields.map { field =>
            if (field.isOption) {
              field.datatype match {
                case ScalaDatatype.List(_) | ScalaDatatype.Map(_) | ScalaDatatype.Option(_) => {
                  s"""(__ \\ "${field.originalName}").write[${field.datatype.name}]"""
                }

                case ScalaDatatype.Singleton(_) => {
                  s"""(__ \\ "${field.originalName}").write[scala.Option[${field.datatype.name}]]"""
                }

              }
            } else {
              s"""(__ \\ "${field.originalName}").write[${field.datatype.name}]"""
            }
          }.mkString(" and\n").indent(4),
          s"  )(unlift(${model.name}.unapply _))",
          s"}"
        ).mkString("\n")
      }
    }
  }

  private[models] def identifier(
    name: String,
    readWrite: ReadWrite
  ): String = {
    s"implicit def json${readWrite.name}${serviceName}$name: play.api.libs.json.${readWrite.name}[$name]"
  }

  private[models] def privateIdentifier(model: ScalaModel): String = {
    if (model.unions.isEmpty) {
      ""
    } else {
      "private "
    }
  }

}
