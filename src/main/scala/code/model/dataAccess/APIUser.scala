/**
Open Bank Project - API
Copyright (C) 2011, 2013, TESOBE / Music Pictures Ltd

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com
TESOBE / Music Pictures Ltd
Osloerstrasse 16/17
Berlin 13359, Germany

  This product includes software developed at
  TESOBE (http://www.tesobe.com/)
  by
  Ayoub Benali: ayoub AT tesobe DOT com

 */
package code.model.dataAccess

import net.liftweb.mapper._
import net.liftweb.util.Props

import code.model.User

class APIUser extends LongKeyedMapper[APIUser] with User with ManyToMany with OneToMany[Long, APIUser]{
  def getSingleton = APIUser
  def primaryKeyField = id

  object id extends MappedLongIndex(this)
  object email extends MappedEmail(this, 48){
    override def required_? = false
  }
  object firstName extends MappedString(this, 100){
    override def required_? = false
  }
  object lastName extends MappedString(this, 100){
    override def required_? = false
  }
  object provider_ extends MappedString(this, 100){
    override def defaultValue = Props.get("hostname","")
  }

  /**
  * the id of the user at that provider
  */
  object providerId extends MappedString(this, 100){
    override def defaultValue = java.util.UUID.randomUUID.toString
  }


  def emailAddress = email.get
  
  def idGivenByProvider = providerId.get 
  def apiId = id.get.toString
  
  def theFirstName : String = firstName.get
  def theLastName : String = lastName.get
  def provider = provider_.get

}

object APIUser extends APIUser with LongKeyedMetaMapper[APIUser]{
    override def dbIndexes = UniqueIndex(provider_, providerId) ::super.dbIndexes
}