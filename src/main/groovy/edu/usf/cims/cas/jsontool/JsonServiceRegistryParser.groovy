package edu.usf.cims.cas.jsontool

import groovy.json.*
import org.springframework.util.AntPathMatcher
import org.jasig.cas.services.RegisteredService
import org.jasig.cas.services.RegisteredServiceImpl
import org.jasig.cas.services.RegexRegisteredService
import net.unicon.cas.addons.serviceregistry.RegexRegisteredServiceWithAttributes
import net.unicon.cas.addons.serviceregistry.RegisteredServiceWithAttributesImpl

/**
 * Parses/modifies JSON-based CAS service registry file.
 *
 * @author Eric Pierce (epierce@usf.edu)
 * @version 0.4.2
 */
class JsonServiceRegistryParser {

	private def jsonData
	private def casAttributes = []
	private def requiredExtraAttributes = []
	private def allowedExtraAttributes = []
	private def defaultTheme = "default"

	/**
	* Class constructor.
	*/
	def JsonServiceRegistryParser() {
		jsonData = [ services:[] ]
	}

	def setJsonData(myjsonData) {
		if (! myjsonData.services) throw new JsonServiceRegistryFileFormatException()
		jsonData = myjsonData
	}

	def setCasAttributes(attributes){
    casAttributes = attributes
	}

  def buildAttributeReleaseList(myAttributes){
    def attributes = []
    if(myAttributes){
      myAttributes.each() { attribute ->
        //Remove attributes that strt with -
        if(! attribute.startsWith("-")) {
          if(attribute.startsWith("\\-")) attribute = attribute[1..-1]
          attributes << attribute
        }
      }
    }
    return attributes
  }

	def setDefaultTheme(theme){
		defaultTheme = theme
	}

	def setExtraServiceAttributes(allowedAttributes,requiredAttributes){
		allowedExtraAttributes = allowedAttributes + requiredAttributes
		requiredExtraAttributes = requiredAttributes
	}

	def checkCasAttributes(releaseList){
		releaseList.each { attribute ->
      if (attribute.startsWith("-") || attribute.startsWith("\\-")) attribute = attribute[1..-1]
			if(! casAttributes.contains(attribute)) throw new IllegalArgumentException("Attribute ${attribute} can not be released")
    }
	}

	def checkRequiredExtraAttributes(extraServiceAttributes){
		requiredExtraAttributes.each { attribute ->
			if(! extraServiceAttributes["${attribute}"]){
				throw new IllegalArgumentException("Attribute ${attribute} is required!")
			}
		}
	}

	def findHighestId(){
		def idList = []
		jsonData.services.each { service ->
			idList.add(service.id)
		}
		idList.max() as Integer ?: 0
	}

	def createService(options){

		def extraServiceAttributes = [createdDate:String.format('%tF %<tT %<tz', new Date())]

		if(!options.name) throw new IllegalArgumentException('Service name required!')
		if(!options.desc) throw new IllegalArgumentException('Service description required!')
		if(!options.pattern) throw new IllegalArgumentException('Regex or Ant pattern required!')
		if(!options.url) throw new IllegalArgumentException('Test URL required!')

		if(options.release) checkCasAttributes(options.releases)

    extraServiceAttributes = addExtraServiceAttributes(options, extraServiceAttributes)

		checkRequiredExtraAttributes(extraServiceAttributes)

		if((options.userAttribute) && (! options.releases.contains(options.userAttribute))) {
			throw new IllegalArgumentException("Username Attribute ${options.userAttribute} is not being released for this service")
		}

		def jsonService
 		if(options.pattern.startsWith('^')){
      	jsonService = new RegexRegisteredServiceWithAttributes()
			def regexPattern = ~/${options.pattern}/
			if (! regexPattern.matcher(options.url).matches()) throw new IllegalArgumentException('Test URL does not match pattern')
		}else{
			jsonService = new RegisteredServiceWithAttributesImpl()
			def matcher = new AntPathMatcher()
			if (! matcher.match(options.pattern.toLowerCase(), options.url.toLowerCase())) throw new IllegalArgumentException('Test URL does not match pattern')
		}
		jsonService.with {
			setId findHighestId()+1
			setName options.name
			setDescription options.desc
			setServiceId options.pattern
			setTheme options.theme ?: defaultTheme
			setAllowedAttributes buildAttributeReleaseList(options.releases)
			setExtraAttributes extraServiceAttributes
      if(options.userAttribute) setUsernameAttribute options.userAttribute
			if(options.disable) setEnabled false   //Services are enabled by default
			if(options.disableSSO) setSsoEnabled false   //SSO is enabled by default
			if(options.enableAnonymous) setAnonymousAccess true   //Anonymous is disabled by default
			if(options.evalOrder) {
				setEvaluationOrder options.evalOrder.toInteger()
			} else {
				setEvaluationOrder 100
			}
			setAllowedToProxy options.enableProxy ?: false   //Proxy is enabled by default in CAS, but IMHO it should be disabled by default
		}
		addService(jsonService)
	}

	def addService(service){
		jsonData.services.add(service)
	}

	def addExtraServiceAttributes(options, extraServiceAttributes){

		if(options.extraAttributes){

      def attributeMap = [:]

      //loop through the attribute list and create a map
      0.step( options.extraAttributes.size(), 2 ) {
        def key = options.extraAttributes[it]  // Even numbered list elements are keys
        def value = options.extraAttributes[it + 1] //Odd numbered are the values

        if (value == 'REMOVE') {
          //REMOVE is a special case value: remove the attribute from the map
          if(extraServiceAttributes[key]) extraServiceAttributes.remove(key)
        } else {
          // Create an array (if it doesn't exist) and store this value in it
          if(! attributeMap[key]) attributeMap[key] = []

          // Remove the value if it starts with a minus sign
          if (value.startsWith('-')) {
            attributeMap[key] = attributeMap[key] - value[1..-1]
          } else {
            // Values that should start with a dash need to be escaped
            if (value.startsWith("\\-")) value = value[1..-1]
            attributeMap[key] =  attributeMap[key] + value
          }
        }
      }

  		//check that all attributes are allowed
  		if (allowedExtraAttributes.size() > 0) {
  			attributeMap.each {
  				if(! allowedExtraAttributes.contains(it.key)) throw new IllegalArgumentException("Attribute ${it.key} is not allowed!")
  			}
  		}

  		//populate the extraServiceAttributes map
  		attributeMap.each {
  				extraServiceAttributes.put(it.key,it.value)
  		}
    }

    //Add the authorization attributes
    if(options.authzName && options.authzValue && options.authzUrl){
      extraServiceAttributes["authzAttributes"] = [(options.authzName): options.authzValues]
      extraServiceAttributes["unauthorizedRedirectUrl"] = options.authzUrl
    }

    //Add the MultiFactor authentication attributes
    if((options.mfaAttr)&&(options.mfaValue)){
    	extraServiceAttributes[options.mfaAttr] = options.mfaValue
    }
    if((options.mfaUserAttr)&&(options.mfaUser)){
    	extraServiceAttributes[options.mfaUserAttr] = options.mfaUsers
    }
    return extraServiceAttributes
	}

  def addAuthzAttribute(options, extraServiceAttributes){
    if(options.authzName && options.authzValue){
      extraServiceAttributes["authzAttributes"] = [(options.authzName): options.authzValues]
    }

    return extraServiceAttributes
  }

	def removeService(id){
		def removeThisService = findById(id)
		jsonData.services.remove(removeThisService[0])
	}

	def modifyService(origService,options){
		//make sure the attributes (if requested) can be released
		if(options.release && options.release != "REMOVE") checkCasAttributes(options.releases)

		if(options.enable) origService.enabled = true
		if(options.disable) origService.enabled = false
		if(options.enableSSO) origService.ssoEnabled = true
		if(options.disableSSO) origService.ssoEnabled = false
		if(options.enableAnonymous) origService.anonymousAccess = true
		if(options.disableAnonymous) origService.anonymousAccess = false
		if(options.enableProxy) origService.allowedToProxy = true
		if(options.disableProxy) origService.allowedToProxy = false
		if(options.evalOrder) origService.evaluationOrder = options.evalOrder.toInteger()
		if(options.name) origService.name = options.name
		if(options.desc) origService.description = options.desc
		if(options.theme) origService.theme = options.theme
		if(options.release) {
			if(options.releases == ["REMOVE"]) {
				origService.allowedAttributes = []
				origService.usernameAttribute = null
			} else {
        options.releases.each() { attribute ->
          // Remove the value if it starts with a minus sign
          if (attribute.startsWith('-')) {
            origService.allowedAttributes = origService.allowedAttributes - attribute[1..-1]
          } else {
            // values that should start with a dash need to be escaped
            if (attribute.startsWith('\\-')) attribute = attribute[1..-1]
            origService.allowedAttributes = origService.allowedAttributes + attribute
          }
        }
			}
      origService.allowedAttributes = origService.allowedAttributes.unique()
		}

		if(options.userAttribute) {
			if (! origService.allowedAttributes.contains(options.userAttribute)) {
				throw new IllegalArgumentException("Username Attribute ${options.userAttribute} is not being released for this service")
			} else {
				origService.usernameAttribute = options.userAttribute
			}
		}
		if(options.pattern) origService.serviceId = options.pattern
		origService.extraAttributes = addExtraServiceAttributes(options,origService.extraAttributes)
		origService.extraAttributes.modifiedDate = String.format('%tF %<tT  %<tz', new Date())
		checkRequiredExtraAttributes(origService.extraAttributes)
		return origService
	}

	def searchForService(options){
		def searchData = [ services:[] ]

		if(options.url){
			searchData.services = findByUrl(options.url)
		}else if(options.id){
			searchData.services = findById(options.id)
		}else if(options.name){
			searchData.services = findByName(options.name)
		}else {
			throw new IllegalArgumentException('URL, id, or name required!')
		}
		return searchData
	}

	def findById(id){
		def foundService = []
		jsonData.services.each { service ->
			if(id.toInteger() == service.id) foundService.add(service)
		}
		if (foundService.size != 1) JsonServiceParserException("Found ${foundService.size} Service IDs that match!")
		return foundService
	}

	def findByName(namePattern){
		def foundService = []
		def pattern = ~/${namePattern}/
		jsonData.services.each { service ->
			if(pattern.matcher(service.name).matches()) foundService.add(service)
		}
		return foundService
	}

	def findByUrl(url){
		def foundService = []
		jsonData.services.each { service ->
			if(service.serviceId.startsWith("^")){
				def pattern = ~/${service.serviceId}/
				if(pattern.matcher(url).matches()) foundService.add(service)
			} else {
				def matcher = new AntPathMatcher()
				if (matcher.match(service.serviceId.toLowerCase(), url.toLowerCase())) foundService.add(service)
			}
		}
		return foundService
	}
}

class JsonServiceRegistryFileFormatException extends RuntimeException{}

class JsonServiceParserException extends RuntimeException{}